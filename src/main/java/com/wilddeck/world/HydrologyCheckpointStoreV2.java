package com.wilddeck.world;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.*;

/**
 * Single-writer local-file checkpoint store. World state and hydrology inbox
 * are serialized in ONE checksummed file and replaced with an atomic rename.
 * A crash before the rename leaves the old checkpoint authoritative.
 *
 * Requires a filesystem supporting atomic moves and durable file/directory
 * sync. This is not a distributed transaction, cloud object-store adapter,
 * multiplayer lock, or transactional integration with an external database.
 */
public final class HydrologyCheckpointStoreV2 {
    private HydrologyCheckpointStoreV2(){}
    private static final int MAGIC=0x57444832; // WDH2
    private static final int VERSION=1;
    private static final int MAX_FIELD_BYTES=32*1024*1024;
    private static final int MAX_FILE_BYTES=2*MAX_FIELD_BYTES+128;

    public static void save(Path destination,HydrologyCheckpointV2.Checkpoint checkpoint)
            throws IOException {
        Objects.requireNonNull(destination);
        Objects.requireNonNull(checkpoint);
        Path target=destination.toAbsolutePath();
        Path parent=target.getParent();
        if(parent==null||!Files.isDirectory(parent))
            throw new IOException("Checkpoint parent directory must exist");
        byte[] encoded=encode(checkpoint);
        Path temporary=Files.createTempFile(parent,target.getFileName().toString()+".",".pending");
        try {
            try(FileChannel channel=FileChannel.open(temporary,WRITE,TRUNCATE_EXISTING)){
                ByteBuffer buffer=ByteBuffer.wrap(encoded);
                while(buffer.hasRemaining())channel.write(buffer);
                channel.force(true);
            }
            // Deliberately no non-atomic fallback: partial replacement is worse.
            Files.move(temporary,target,ATOMIC_MOVE,REPLACE_EXISTING);
            // Sync directory entry as well as file contents on supported filesystems.
            try(FileChannel directory=FileChannel.open(parent,READ)){
                directory.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static HydrologyCheckpointV2.Checkpoint load(Path destination)
            throws IOException {
        Objects.requireNonNull(destination);
        if(Files.size(destination)>MAX_FILE_BYTES)
            throw new IOException("Checkpoint exceeds maximum size");
        return decode(Files.readAllBytes(destination));
    }

    private static byte[] encode(HydrologyCheckpointV2.Checkpoint checkpoint)
            throws IOException {
        byte[] world=checkpoint.worldState().getBytes(StandardCharsets.UTF_8);
        byte[] inbox=checkpoint.inboxState().getBytes(StandardCharsets.UTF_8);
        if(world.length>MAX_FIELD_BYTES||inbox.length>MAX_FIELD_BYTES)
            throw new IOException("Checkpoint field too large");
        var body=new ByteArrayOutputStream();
        try(var out=new DataOutputStream(body)){
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(checkpoint.epoch());
            out.writeInt(world.length);
            out.write(world);
            out.writeInt(inbox.length);
            out.write(inbox);
        }
        byte[] payload=body.toByteArray();
        var result=new ByteArrayOutputStream(payload.length+32);
        result.write(payload);
        result.write(sha256(payload));
        return result.toByteArray();
    }

    private static HydrologyCheckpointV2.Checkpoint decode(byte[] bytes)
            throws IOException {
        if(bytes.length<4+4+8+4+4+32||bytes.length>MAX_FILE_BYTES)
            throw new IOException("Invalid checkpoint length");
        int payloadLength=bytes.length-32;
        byte[] payload=Arrays.copyOf(bytes,payloadLength);
        byte[] checksum=Arrays.copyOfRange(bytes,payloadLength,bytes.length);
        if(!MessageDigest.isEqual(checksum,sha256(payload)))
            throw new IOException("Checkpoint checksum mismatch");
        try(var in=new DataInputStream(new ByteArrayInputStream(payload))){
            if(in.readInt()!=MAGIC||in.readInt()!=VERSION)
                throw new IOException("Unsupported checkpoint version");
            long epoch=in.readLong();
            int worldLength=in.readInt();
            if(worldLength<0||worldLength>MAX_FIELD_BYTES||worldLength>in.available())
                throw new IOException("Invalid world-state length");
            String world=decodeUtf8(in.readNBytes(worldLength));
            int inboxLength=in.readInt();
            if(inboxLength<0||inboxLength>MAX_FIELD_BYTES||inboxLength!=in.available())
                throw new IOException("Invalid inbox length or trailing data");
            String inbox=decodeUtf8(in.readNBytes(inboxLength));
            try {
                return new HydrologyCheckpointV2.Checkpoint(epoch,world,inbox);
            }catch(IllegalArgumentException ex){
                throw new IOException("Invalid checkpoint state",ex);
            }
        }catch(EOFException ex){
            throw new IOException("Truncated checkpoint",ex);
        }
    }

    private static String decodeUtf8(byte[] data) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
        }catch(CharacterCodingException ex){
            throw new IOException("Malformed checkpoint UTF-8",ex);
        }
    }

    private static byte[] sha256(byte[] data){
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        }catch(NoSuchAlgorithmException ex){
            throw new IllegalStateException("SHA-256 unavailable",ex);
        }
    }
}
