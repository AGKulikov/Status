/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.adb;

/** Exact reviewed 32-byte ARM64 patch. No vehicle property IDs or compatibility guesses. */
public final class HudLcaPatch {
    public static final String MODULE="vendor.ecarx.xma.automotive.vehicle@1.0-modules.so";
    public static final String BACKUP="/data/local/tmp/natro-hud-lca";
    public enum Mode {
        ORIGINAL("Оригинал · IntellDrv", "b5bcf37a0aa8a31bf69296989ba7730ded54f99998f43a6313af4e4a4180cca6", "820000d0830000d0423c289163801991e0070032e1031faae4031a2a2fd4ff97"),
        SIMPLE("Simple", "ab2fad138620c59871634daeae3c8206eee43ee0fc07f0d83cc68c91c8308a0f", "5f070071e80700321a019a1a1f2003d51f2003d51f2003d51f2003d51f2003d5"),
        GUIDE("IntellGuide", "6c492eabff9a7df3febc0039a7f1dbda50b2b7191e6b2ec0507843433586421e", "5f070071e8031f2a1a019a1a1f2003d51f2003d51f2003d51f2003d51f2003d5"),
        AR("AR", "bcdd2395c0c760961156c56d5fbea3615c87e1fbde4198f573918723ec6b4fa4", "5f070071e8031f321a019a1a1f2003d51f2003d51f2003d51f2003d51f2003d5");
        public final String label,sha256,hex;
        Mode(String label,String sha256,String hex){this.label=label;this.sha256=sha256;this.hex=hex;}
    }
    private HudLcaPatch(){}
    private static String find() {return "set -e\nf='/vendor/lib64/"+MODULE+"'\n[ -f \"$f\" ] || f='/system/vendor/lib64/"+MODULE+"'\n[ -f \"$f\" ]\n[ ! -L \"$f\" ]\n";}
    private static String hash(String file){return "sha256sum \""+file+"\" | cut -d ' ' -f1";}
    private static String block(String file,Mode mode) {
        StringBuilder octal=new StringBuilder();for(int i=0;i<mode.hex.length();i+=2)octal.append(String.format(java.util.Locale.ROOT,"\\%03o",Integer.parseInt(mode.hex.substring(i,i+2),16)));
        return "printf '"+octal+"' | dd of=\""+file+"\" bs=1 seek=124248 conv=notrunc 2>/dev/null\n";
    }
    private static String known() {
        StringBuilder script=new StringBuilder("[ \"$(wc -c < \"$f\")\" -eq 268424 ]\nh=$("+hash("$f")+")\nb=$(od -An -tx1 -j 124248 -N 32 \"$f\" | tr -d ' \\r\\n')\ncase \"$h:$b\" in\n");
        for(Mode mode:Mode.values())script.append(mode.sha256).append(':').append(mode.hex).append(") mode='").append(mode.name()).append("';;\n");
        return script+"*) echo 'Неизвестная версия: запись запрещена'; exit 40;;\nesac\n";
    }
    public static String inspect(){return find()+known()+"printf 'Файл: %s\\nРежим: %s\\nSHA-256: %s\\n' \"$f\" \"$mode\" \"$h\"\nls -lZ \"$f\"\nif [ -f '"+BACKUP+"/journal' ]; then echo 'Есть незавершённый журнал; проверьте результат или восстановите оригинал'; cat '"+BACKUP+"/journal'; fi\n";}
    public static String install(Mode target) {
        if(target==null)throw new IllegalArgumentException("Mode required");
        String directory=BACKUP;
        return find()+"[ \"$(id -u)\" = 0 ]\n[ \"$(getprop ro.build.version.sdk)\" = 28 ]\ncase \"$(getprop ro.product.cpu.abi)\" in arm64-v8a) ;; *) exit 41;; esac\n"+known()
            +"[ \"$(stat -c '%u:%g:%a' \"$f\")\" = '0:0:644' ]\ncontext=$(ls -lZ \"$f\" | tr ' ' '\\n' | sed -n '/^u:object_r:[a-zA-Z0-9_]*:s0$/p')\n[ -n \"$context\" ]\n"
            +"d='"+directory+"'\n[ ! -L \"$d\" ]\nmkdir -p \"$d\"\nchmod 0700 \"$d\"\n"
            +"[ ! -e \"$d/operation-lock\" ] || { echo 'Предыдущая операция требует проверки; автоматический повтор запрещён'; exit 42; }\nmkdir \"$d/operation-lock\"\ntrap 'rmdir \"$d/operation-lock\" 2>/dev/null || true' EXIT\n"
            +"[ ! -L \"$d/original.so\" ]\ncp \"$f\" \"$d/original-new.so\"\n"+block("$d/original-new.so",Mode.ORIGINAL)
            +"[ \"$("+hash("$d/original-new.so")+")\" = '"+Mode.ORIGINAL.sha256+"' ]\n"
            +"if [ -f \"$d/original.so\" ]; then [ \"$("+hash("$d/original.so")+")\" = '"+Mode.ORIGINAL.sha256+"' ]; else mv \"$d/original-new.so\" \"$d/original.so\"; fi\nchmod 0600 \"$d/original.so\"\nsync\n"
            +"cp \"$d/original.so\" \"$d/target.so\"\n"+block("$d/target.so",target)
            +"[ \"$("+hash("$d/target.so")+")\" = '"+target.sha256+"' ]\n"
            +"printf '%s\\n%s\\n%s\\n' \"$f\" \"$h\" '"+target.sha256+"' > \"$d/journal-new\"\nmv \"$d/journal-new\" \"$d/journal\"\nsync\n"
            +"case \"$f\" in /vendor/*) mount -o remount,rw /vendor;; /system/vendor/*) mount -o remount,rw /system;; *) exit 43;; esac\n"
            +"[ ! -L \"$f.hudlab-original.bak\" ]\nif [ -e \"$f.hudlab-original.bak\" ]; then [ \"$("+hash("$f.hudlab-original.bak")+")\" = '"+Mode.ORIGINAL.sha256+"' ]; else cp \"$d/original.so\" \"$f.hudlab-original.bak\"; chown 0:0 \"$f.hudlab-original.bak\"; chmod 0644 \"$f.hudlab-original.bak\"; chcon \"$context\" \"$f.hudlab-original.bak\"; fi\n"
            +"[ ! -L \"$f.natro-new\" ]\ncp \"$d/target.so\" \"$f.natro-new\"\nchown 0:0 \"$f.natro-new\"\nchmod 0644 \"$f.natro-new\"\nchcon \"$context\" \"$f.natro-new\"\n[ \"$("+hash("$f.natro-new")+")\" = '"+target.sha256+"' ]\nsync\nmv -f \"$f.natro-new\" \"$f\"\nsync\n"
            +"[ \"$("+hash("$f")+")\" = '"+target.sha256+"' ]\n[ \"$(stat -c '%u:%g:%a' \"$f\")\" = '0:0:644' ]\nls -lZ \"$f\" | grep -F \"$context\"\nmv \"$d/journal\" \"$d/last-completed\"\nsync\necho 'NATRO_HUD_PATCH_VERIFIED_"+target.name()+"'\n";
    }
}
