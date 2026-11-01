package com.wilddeck.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** Authenticated opaque saves: never send hidden hands or deck order as plaintext. */
final class MatchSaveCodec {
 static final int MAX_JSON=1_048_576;
 record Save(int version,long revision,String message,RndMatchEngine.Snapshot match){}
 private final SecretKeySpec key;
 private final SecureRandom random=new SecureRandom();
 private final ObjectMapper json=new ObjectMapper();
 MatchSaveCodec(byte[] key){
  if(key.length!=32)throw new IllegalArgumentException("Save key must contain 32 bytes");
  this.key=new SecretKeySpec(key.clone(),"AES");
 }
 static MatchSaveCodec fromEnvironment(){
  String value=System.getenv("WILD_DECK_SAVE_KEY");
  if(value==null)throw new IllegalStateException("Set a stable base64 WILD_DECK_SAVE_KEY (32 random bytes) before starting");
  try{return new MatchSaveCodec(Base64.getDecoder().decode(value));}
  catch(IllegalArgumentException e){throw new IllegalStateException("WILD_DECK_SAVE_KEY must be base64 encoding of 32 bytes");}
 }
 String seal(String session,Save save){
  try{
   byte[] plain=json.writeValueAsBytes(save);
   if(plain.length>MAX_JSON)throw new IllegalStateException("Match save is too large");
   byte[] nonce=new byte[12];random.nextBytes(nonce);
   Cipher cipher=cipher(Cipher.ENCRYPT_MODE,session,nonce);
   byte[] encrypted=cipher.doFinal(plain);
   byte[] token=new byte[nonce.length+encrypted.length];
   System.arraycopy(nonce,0,token,0,nonce.length);System.arraycopy(encrypted,0,token,nonce.length,encrypted.length);
   return "wd1."+Base64.getUrlEncoder().withoutPadding().encodeToString(token);
  }catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException("Could not save match",e);}
 }
 Save open(String session,String token){
  try{
   if(token==null||token.length()>1_500_000||!token.startsWith("wd1."))throw new IllegalArgumentException();
   byte[] bytes=Base64.getUrlDecoder().decode(token.substring(4));
   if(bytes.length<29||bytes.length>MAX_JSON+28)throw new IllegalArgumentException();
   byte[] plain=cipher(Cipher.DECRYPT_MODE,session,Arrays.copyOf(bytes,12)).doFinal(bytes,12,bytes.length-12);
   Save save=json.readValue(plain,Save.class);
   if(save.version()!=1||save.revision()<0||save.message()==null||save.match()==null)throw new IllegalArgumentException();
   return save;
  }catch(Exception e){throw new IllegalArgumentException("Save is damaged, belongs to another browser, or uses an unsupported version");}
 }
 private Cipher cipher(int mode,String session,byte[] nonce)throws Exception{
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
  cipher.init(mode,key,new GCMParameterSpec(128,nonce));
  cipher.updateAAD(("WildDeck/save/1/"+session).getBytes(StandardCharsets.UTF_8));
  return cipher;
 }
}
