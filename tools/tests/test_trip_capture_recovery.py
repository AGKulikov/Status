"""Synthetic replay of the observed stderr contamination; no user packet fixtures."""
import unittest

from tools.tests.test_trip_screen_collector import SOURCE, module, synthetic_pcap

recovery = module('trip_capture_recovery', SOURCE / 'recover_screen_capture.py')


class TripCaptureRecoveryTests(unittest.TestCase):
    def contaminated(self, clean):
        return clean[:24] + recovery.BANNER + clean[24:] + recovery.FOOTER

    def test_all_packet_bytes_survive_observed_stderr_framing(self):
        clean = synthetic_pcap()
        recovered, report = recovery.recover_capture(self.contaminated(clean))
        self.assertEqual(recovered, clean)
        self.assertEqual(report['observation']['counts']['trip_messages'], 20)
        self.assertEqual(report['ipv4_checksums_valid'], 20)
        self.assertEqual(report['udp_checksums_present_and_valid'], 0)

    def test_interleaved_text_inside_packet_is_never_silently_removed(self):
        damaged = bytearray(self.contaminated(synthetic_pcap()))
        damaged[200:200] = recovery.BANNER
        with self.assertRaises(ValueError):
            recovery.recover_capture(bytes(damaged))

    def test_partial_capture_and_nonzero_drop_footer_are_rejected(self):
        cases = [self.contaminated(synthetic_pcap(19)),
                 self.contaminated(synthetic_pcap()).replace(b'0 packets dropped', b'1 packets dropped')]
        for data in cases:
            with self.subTest(size=len(data)), self.assertRaises(ValueError):
                recovery.recover_capture(data)

    def test_bad_checksum_is_rejected_even_with_valid_record_lengths(self):
        damaged = bytearray(self.contaminated(synthetic_pcap()))
        first_ip = 24 + len(recovery.BANNER) + 16 + 14
        damaged[first_ip + 8] ^= 1
        with self.assertRaisesRegex(ValueError, 'checksum'):
            recovery.recover_capture(bytes(damaged))
