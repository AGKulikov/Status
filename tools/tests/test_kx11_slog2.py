import struct
import unittest

from tools.inspect_kx11_slog2 import inspect


def fixture():
    data = bytearray(8192)
    struct.pack_into("<Q", data, 0xe0, 0x100000)
    struct.pack_into("<I", data, 0xe8, 1)
    struct.pack_into("<I", data, 0x134, 4096)
    struct.pack_into("<Q", data, 0x138, 0x100000)
    struct.pack_into("<I", data, 0x140, 4096 - 0xf0)
    return data


def packet(text=b"source rect", kind=0):
    payload = text + b"\0"
    return struct.pack("<HHIHBBHH", 0xbdcf, 16 + len(payload), 123, 7, 24, kind, 0, 0) + payload


class Slog2InspectionTest(unittest.TestCase):
    def test_text_and_encoded_type_are_distinct(self):
        data = fixture()
        for offset, kind in [(0, 0), (64, 1), (128, 2)]:
            p = packet(kind=kind)
            data[4096 + offset:4096 + offset + len(p)] = p
        records = inspect(data)["buffers"][0]["candidates"]
        self.assertEqual([r["kind"] for r in records], [0, 1, 2])
        self.assertEqual(records[0]["text"], "source rect")
        self.assertNotIn("text", records[1])
        self.assertNotIn("text", records[2])

    def test_record_crossing_ring_end(self):
        data = fixture()
        p = packet()
        for n, value in enumerate(p):
            data[4096 + (4088 + n) % 4096] = value
        records = inspect(data)["buffers"][0]["candidates"]
        self.assertEqual(len(records), 1)
        self.assertTrue(records[0]["wrapped"])
        self.assertEqual(records[0]["text"], "source rect")

    def test_encoded_format_is_not_reported_as_formatted_text(self):
        data = fixture()
        p = bytearray(packet(b"x=%d", kind=1))
        p += b"\0\x04\x00\x12\x34\x56\x78"
        struct.pack_into("<H", p, 2, len(p))
        data[4096:4096 + len(p)] = p
        record = inspect(data)["buffers"][0]["candidates"][0]
        self.assertEqual(record["format_template"], "x=%d")
        self.assertEqual(record["encoded_arguments_hex"], "040012345678")
        self.assertNotIn("text", record)

    def test_truncated_or_inconsistent_file_rejected(self):
        data = fixture()
        with self.assertRaises(ValueError):
            inspect(data[:-1])
        struct.pack_into("<I", data, 0x140, 1)
        with self.assertRaises(ValueError):
            inspect(data)

    def test_bad_packet_and_unknown_header(self):
        data = fixture()
        p = packet()[:-1] + b"x"
        data[4096:4096 + len(p)] = p
        self.assertEqual(inspect(data)["buffers"][0]["candidates"], [])
        struct.pack_into("<I", data, 0xe8, 5)
        with self.assertRaises(ValueError):
            inspect(data)


if __name__ == "__main__":
    unittest.main()
