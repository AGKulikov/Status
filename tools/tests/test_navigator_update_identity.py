"""Synthetic binary XML regression; actual APK boundary is checked by the signing gate."""
import importlib.util,struct,unittest
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from sign_navigation_update_candidate import identity,OLD_CODE,NEW_CODE,NEW_NAME
from patch_music_manifest import chunks

def fixture(code=OLD_CODE):
    strings=['manifest','versionCode','versionName','30.3.0'];blob=b'';offsets=[]
    for s in strings:
        offsets.append(len(blob));b=s.encode();blob+=bytes([len(s),len(b)])+b+b'\0'
    blob+=b'\0'*((-len(blob))%4)
    pool=struct.pack('<HHI5I',1,28,44+len(blob),4,0,256,44,0)+struct.pack('<4I',*offsets)+blob
    attrs=struct.pack('<IIIHBBI',0xffffffff,1,0xffffffff,8,0,0x10,code)+struct.pack('<IIIHBBI',0xffffffff,2,3,8,0,3,3)
    node=struct.pack('<HHIII',0x102,16,76,1,0xffffffff)+struct.pack('<IIHHHHHH',0xffffffff,0,20,20,2,0,0,0)+attrs
    payload=pool+node;return struct.pack('<HHI',3,8,len(payload)+8)+payload

class NavigatorUpdateIdentity(unittest.TestCase):
    def test_only_two_attributes_and_appended_string_change(self):
        original=fixture();patched=identity(original);before=list(chunks(original));after=list(chunks(patched))
        self.assertEqual(len(before),len(after));self.assertIn(NEW_NAME.encode(),after[0][1])
        old=bytearray(before[1][1]);new=after[1][1]
        struct.pack_into('<I',old,52,NEW_CODE);struct.pack_into('<I',old,64,4);struct.pack_into('<I',old,72,4)
        self.assertEqual(bytes(old),new)
    def test_unknown_or_already_incremented_code_is_rejected(self):
        for code in (0,NEW_CODE):
            with self.assertRaises(ValueError):identity(fixture(code))
if __name__=='__main__':unittest.main()
