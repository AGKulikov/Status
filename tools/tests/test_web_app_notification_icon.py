"""Replay production automation validation: long ANCS icon ref must not drop notification fields."""
from pathlib import Path
import subprocess,tempfile,unittest
from test_map_visibility_recovery import method,ROOT

# Exact validation from released 3.1.0, retained so CI needs no Git history.
OLD_VALIDATION = 'private static void validatePatch(JSONObject patch) {\n        if (patch.toString().length() > AutomationContract.MAX_PAYLOAD_CHARS) {\n            throw new IllegalArgumentException("Automation payload is too large");\n        }\n        checkLength(patch, "text", 8192);\n        checkLength(patch, "state", 8192);\n        checkLength(patch, "value", 8192);\n        checkLength(patch, "color", 64);\n        checkLength(patch, "background_color", 64);\n        checkLength(patch, "border_color", 64);\n        checkLength(patch, "icon_tint", 64);\n        checkLength(patch, "icon_background_color", 64);\n        checkLength(patch, "icon_outline_color", 64);\n        checkLength(patch, "icon", 64);\n        checkLength(patch, "request_id", 128);\n        checkLength(patch, "source", 64);\n    }\nprivate static void checkLength(JSONObject patch, String field, int max) {\n        if (patch.has(field) && !patch.isNull(field)\n                && String.valueOf(patch.opt(field)).length() > max) {\n            throw new IllegalArgumentException(field + " is too long");\n        }\n    }'

class WebAppIconReplay(unittest.TestCase):
    def test_long_web_push_identifier_preserves_icon_and_text_with_bounds(self):
        path='app/src/main/java/dezz/status/widget/automation/AutomationStateStore.java'
        source=(ROOT/path).read_text()
        current='\n'.join(method(source,n) for n in ('private static void validatePatch(', 'private static void checkLength(', 'private static void checkIconLength('))
        old=OLD_VALIDATION
        harness=r'''public class WebAppReplay {
        static class JSONObject extends java.util.LinkedHashMap<String,Object> {
          boolean has(String k){return containsKey(k);}boolean isNull(String k){return get(k)==null;}
          Object opt(String k){return get(k);}String optString(String k,String fallback){Object o=get(k);return o==null?fallback:o.toString();}
        }
        static class AutomationContract {static final int MAX_PAYLOAD_CHARS=65536;}
        static class Before {OLD}
        CURRENT
        static void check(boolean v){if(!v)throw new AssertionError();}
        static boolean rejected(JSONObject p){try{validatePatch(p);return false;}catch(IllegalArgumentException expected){return true;}}
        public static void main(String[] args){
          JSONObject p=new JSONObject();String id="com.apple.WebKit.PushBundle."+"0".repeat(36);
          String icon="phone-app:"+id;check(icon.length()>64);p.put("icon",icon);p.put("text","Web notification");p.put("visible",true);
          boolean oldRejected=false;try{Before.validatePatch(p);}catch(IllegalArgumentException expected){oldRejected=true;}check(oldRejected);
          validatePatch(p);check(icon.equals(p.get("icon"))&&"Web notification".equals(p.get("text"))&&Boolean.TRUE.equals(p.get("visible")));
          p.put("icon","phone-app:"+"a".repeat(512));validatePatch(p);
          p.put("icon","phone-app:"+"a".repeat(513));check(rejected(p));
          p.put("icon","x".repeat(65));check(rejected(p));
          p.put("icon","phone-app:"+"../".repeat(24));check(rejected(p));
          p.put("icon","phone-app:"+"a".repeat(63)+"/file");check(rejected(p));
          p.put("icon","phone-app:org.telegram.telegram");validatePatch(p);
          p.put("icon","");validatePatch(p);p.put("text","x".repeat(8193));check(rejected(p));
        }}'''.replace('OLD',old).replace('CURRENT',current)
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);f=root/'WebAppReplay.java';f.write_text(harness)
            p=subprocess.run(['java','com.sun.tools.javac.Main','-d',str(root),str(f)],capture_output=True,text=True)
            self.assertEqual(p.returncode,0,p.stderr)
            p=subprocess.run(['java','-cp',str(root),'WebAppReplay'],capture_output=True,text=True)
            self.assertEqual(p.returncode,0,p.stdout+p.stderr)

if __name__=='__main__':unittest.main()
