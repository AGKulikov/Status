/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.transfer;
public final class LanDownloadReplay {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args)throws Exception {
        LanDownloadGrant grant=new LanDownloadGrant("private-pairing-token","id",100);
        String cookie="natroDownload="+grant.nonce;
        check(grant.accepts(cookie,"id",101));check(!grant.accepts(cookie,"other",101));
        check(!grant.accepts(cookie,"id",600100));check(!grant.accepts(cookie+"; "+cookie,"id",101));
        check(!grant.accepts("natroDownload=wrong","id",101));check(!grant.nonce.contains(grant.owner));
        check(grant.nonce.length()==64&&!new LanDownloadGrant("owner","id",100).nonce.equals(grant.nonce));
        long[] range=LanDownloadGrant.range("bytes=3000000000-",6000000000L);
        check(range[0]==3000000000L&&range[1]==3000000000L);
        check(LanDownloadGrant.range(null,0)[1]==0);
        for(String bad:new String[]{"bytes=-5","bytes=10-9","bytes=100-","bytes=1-2,4-5","bytes=999999999999999999999-"}) {
            try{LanDownloadGrant.range(bad,100);throw new AssertionError(bad);}catch(java.io.IOException expected){}
        }
        System.out.println("PASS export cookie isolation, expiry, ambiguity rejection and 64-bit download ranges");
    }
}
