/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.transfer;

import java.security.*;
import java.nio.charset.StandardCharsets;

/** Short-lived export-only cookie. Never exposes or replaces the remembered pairing key. */
final class LanDownloadGrant {
    final String owner,id,nonce;final long expires;
    LanDownloadGrant(String owner,String id,long now) {
        this.owner=owner;this.id=id;expires=now+10*60*1000L;
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        nonce=value.toString();
    }
    boolean accepts(String cookie,String requestedId,long now) {
        if(now>=expires||!id.equals(requestedId)||cookie==null)return false;
        String value=null;
        for(String part:cookie.split(";"))if(part.trim().startsWith("natroDownload=")) {
            if(value!=null)return false;value=part.trim().substring(14);
        }
        return value!=null&&MessageDigest.isEqual(nonce.getBytes(StandardCharsets.US_ASCII),value.getBytes(StandardCharsets.US_ASCII));
    }
    static long[] range(String header,long size)throws java.io.IOException {
        if(header==null)return new long[]{0,size};
        if(size<=0||!header.matches("bytes=[0-9]{1,18}-[0-9]{0,18}"))throw new java.io.IOException("invalid_range");
        String[] parts=header.substring(6).split("-",-1);
        long start=Long.parseLong(parts[0]),end=parts[1].isEmpty()?size-1:Long.parseLong(parts[1]);
        if(start>=size||end<start)throw new java.io.IOException("unsatisfied_range");
        end=Math.min(end,size-1);return new long[]{start,end-start+1};
    }
}
