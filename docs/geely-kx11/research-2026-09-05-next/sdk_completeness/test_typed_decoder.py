#!/usr/bin/env python3
"""Known DEX vectors that catch the sign and right-zero-extension failures."""
import json,math,pathlib,unittest
from decode_initializers import value
class Vectors(unittest.TestCase):
    def test_known_primitive_vectors(self):
        for raw,expected in [('00ff',-1),('02ff',-1),('2280ff',-128),('23ffff',65535),('04ff',-1),('6400000080',-2147483648),('6401000080',-2147483647),('06ff',-1),('e60000000000000080',-9223372036854775808),('30803f',1.0),('70cdcccc3d',0.10000000149011612),('31f03f',1.0),('1e',None),('1f',False),('3f',True)]:
            with self.subTest(raw=raw):
                b=bytes.fromhex(raw);r,end=value(b,0);self.assertEqual(r['typed_value'],expected);self.assertEqual(end,len(b));self.assertEqual(r['encoded_hex'],raw)
    def test_negative_zero_preserved(self):
        r,_=value(bytes.fromhex('1080'),0);self.assertEqual(math.copysign(1,r['typed_value']),-1)
    def test_nan_preserves_exact_bits(self):
        r,_=value(bytes.fromhex('30c07f'),0);self.assertEqual(r['typed_value'],{'nonfinite_float':'nan','bits_hex':'0x7fc00000'})
    def test_surrogate_json_roundtrip(self):
        s='\ud800';self.assertEqual(json.loads(json.dumps(s,ensure_ascii=True)),s)
if __name__=='__main__':unittest.main()
