package com.wilddeck.world;

import java.util.*;

/**
 * Deterministic, versioned snapshot of transfer receipts and consumed epochs.
 * A runtime must atomically persist this snapshot together with world state.
 */
public final class HydrologyInboxCodecV2 {
    private HydrologyInboxCodecV2(){}
    public static final String HEADER="wilddeck-hydrology-inbox-v3";
    private static final String LEGACY_HEADER="wilddeck-hydrology-inbox-v2";

    public static String encode(HydrologyEpochInboxV2.Snapshot snapshot){
        Objects.requireNonNull(snapshot);
        var out=new StringBuilder(HEADER).append('\n');
        var consumed=new TreeSet<>(snapshot.consumedEpochs());
        for(long epoch:consumed){
            if(epoch<0)throw new IllegalArgumentException("Invalid epoch");
            out.append("C,").append(epoch).append('\n');
        }
        var order=Comparator.comparingLong(HydrologyTransferLedgerV2.Key::epoch)
            .thenComparing(HydrologyTransferLedgerV2.Key::from,HydrologyFlowGraphV2.ADDRESS_ORDER)
            .thenComparing(HydrologyTransferLedgerV2.Key::to,HydrologyFlowGraphV2.ADDRESS_ORDER);
        var receipts=new TreeMap<HydrologyTransferLedgerV2.Key,Double>(order);
        receipts.putAll(snapshot.receipts());
        for(var entry:receipts.entrySet()){
            var key=entry.getKey();double flow=entry.getValue();
            if(!Double.isFinite(flow)||flow<0)throw new IllegalArgumentException("Invalid flow");
            var from=key.from();var to=key.to();
            out.append("R,").append(key.epoch()).append(',')
               .append(from.level()).append(',').append(from.divisions()).append(',')
               .append(from.x()).append(',').append(from.y()).append(',')
               .append(to.x()).append(',').append(to.y()).append(',')
               .append(Double.toString(flow)).append('\n');
        }
        for(var key:receipts.keySet())if(snapshot.delivered().contains(key)){
            var from=key.from();var to=key.to();
            out.append("D,").append(key.epoch()).append(',')
               .append(from.level()).append(',').append(from.divisions()).append(',')
               .append(from.x()).append(',').append(from.y()).append(',')
               .append(to.x()).append(',').append(to.y()).append('\\n');
        }
        return out.toString();
    }

    public static HydrologyEpochInboxV2.Snapshot decode(String encoded){
        Objects.requireNonNull(encoded);
        var lines=encoded.split("\n",-1);
        boolean legacy=lines.length>0&&LEGACY_HEADER.equals(lines[0]);
        if(lines.length<2||(!legacy&&!HEADER.equals(lines[0]))||
           !lines[lines.length-1].isEmpty())
            throw new IllegalArgumentException("Invalid inbox header or terminator");
        var consumed=new HashSet<Long>();
        var receipts=new HashMap<HydrologyTransferLedgerV2.Key,Double>();
        var delivered=new HashSet<HydrologyTransferLedgerV2.Key>();
        for(int i=1;i<lines.length-1;i++){
            var fields=lines[i].split(",",-1);
            try {
                if(fields.length==2&&fields[0].equals("C")){
                    long epoch=Long.parseLong(fields[1]);
                    if(epoch<0||!consumed.add(epoch))
                        throw new IllegalArgumentException("Invalid or duplicate epoch");
                }else if(fields.length==9&&fields[0].equals("R")){
                    long epoch=Long.parseLong(fields[1]);
                    int level=Integer.parseInt(fields[2]);
                    int divisions=Integer.parseInt(fields[3]);
                    var from=new GlobalHydrologyAddressV2.Sample(
                        level,divisions,Long.parseLong(fields[4]),Long.parseLong(fields[5]));
                    var to=new GlobalHydrologyAddressV2.Sample(
                        level,divisions,Long.parseLong(fields[6]),Long.parseLong(fields[7]));
                    var key=new HydrologyTransferLedgerV2.Key(epoch,from,to);
                    double flow=Double.parseDouble(fields[8]);
                    if(!Double.isFinite(flow)||flow<0||receipts.putIfAbsent(key,flow)!=null)
                        throw new IllegalArgumentException("Invalid or duplicate receipt");
                }else if(!legacy&&fields.length==8&&fields[0].equals("D")){
                    var from=new GlobalHydrologyAddressV2.Sample(
                        Integer.parseInt(fields[2]),Integer.parseInt(fields[3]),
                        Long.parseLong(fields[4]),Long.parseLong(fields[5]));
                    var to=new GlobalHydrologyAddressV2.Sample(
                        from.level(),from.divisions(),Long.parseLong(fields[6]),
                        Long.parseLong(fields[7]));
                    var key=new HydrologyTransferLedgerV2.Key(Long.parseLong(fields[1]),from,to);
                    if(!delivered.add(key))
                        throw new IllegalArgumentException("Duplicate delivery marker");
                }else throw new IllegalArgumentException("Invalid inbox row");
            }catch(NumberFormatException e){
                throw new IllegalArgumentException("Invalid inbox number",e);
            }
        }
        return legacy ? new HydrologyEpochInboxV2.Snapshot(receipts,consumed)
            : new HydrologyEpochInboxV2.Snapshot(receipts,consumed,delivered);
    }
}
