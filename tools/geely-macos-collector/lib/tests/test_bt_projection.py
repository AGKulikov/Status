import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('bt_projection', Path(__file__).parents[1] / 'bt_projection.py')
bt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bt)

MAC = 'AA:BB:CC:DD:EE:FF'
UUID = '12345678-1234-1234-1234-123456789abc'
SECRETS = [MAC, UUID, '+79991234567', 'PERSON_SECRET', 'NOTIFICATION_SECRET', 'PAIRING_KEY_SECRET']

class ProjectionTests(unittest.TestCase):
    def assert_private_absent(self, projected):
        encoded = json.dumps(projected)
        for secret in SECRETS:
            self.assertNotIn(secret, encoded)

    def test_dumpsys_scanner_client_roles_and_private_fields(self):
        text = f'''Bluetooth Status
  enabled: true
  state: ON
  time since enabled: 00:02:59.267
Enable log:
  08-15 10:39:11  Enabled  due to SYSTEM_BOOT by android
Bluetooth crashed 0 times
Profile: GattService
GATT Scanner Map
  ru.yandex.yandexnavi (Registered)
  Application ID : 6
  Connections: 0
  UUID: {UUID}
GATT Client Map
  ru.natro.statuswidget (Registered)
  Application ID : 6
  Connections: 1
  Device name: PERSON_SECRET
  LE scans (started/stopped) : 2 / 2
  08-15 10:41:29 - 43924ms : {MAC} (262)
  org.astpepper.hwgps (Registered)
  Application ID : 5
  Connections: 1
GATT Server Map
  Entries: 0
GATT Handle Map
  Requests: 0
Profile: HeadsetClientService
  mCurrentDevice: {MAC}
  mSubscriberInfo: +79991234567
  notification: NOTIFICATION_SECRET
  key: PAIRING_KEY_SECRET
'''
        result = bt.project_dumpsys(text)
        self.assertEqual(result['adapter']['crash_count'], 0)
        roles = [(e['namespace'], e['package'], e.get('application_id')) for e in result['gatt_entries']]
        self.assertIn(('scanner', 'ru.yandex.yandexnavi', 6), roles)
        self.assertIn(('client', 'ru.natro.statuswidget', 6), roles)
        self.assert_private_absent(result)
        self.assertGreater(result['counts']['dropped_lines'], 0)

    def test_unregistered_history_is_not_active_client(self):
        result = bt.project_dumpsys('Profile: GattService\nGATT Client Map\n  ru.natro.statuswidget\n  LE scans (started/stopped) : 0 / 0\nGATT Handle Map\n  Application ID: 99')
        entry = result['gatt_entries'][0]
        self.assertFalse(entry['registered'])
        self.assertNotIn('application_id', entry)
        self.assertNotIn('connections', entry)

    def test_unknown_package_not_echoed(self):
        result = bt.project_dumpsys('GATT Client Map\n  user.private_secret (Registered)\n  Connections: 1')
        self.assertEqual(result['gatt_entries'][0]['package'], 'other_application')
        self.assertNotIn('private_secret', json.dumps(result))

    def test_observed_lifecycle_only(self):
        text = f'''08-15 13:24:09.342 2102 2122 D BluetoothManagerService: enable(ecarx.powersomeip.service):  mBluetooth =android.bluetooth.IBluetooth$Stub$Proxy@bddfb5b mBinding = false mState = BLE_TURNING_ON
08-15 13:24:10.222 2102 2160 E BluetoothManagerService: waitForOnOff time out
08-15 13:25:01.600 4736 4825 D bt_btm_sec: btm_sec_disconnected clearing pending flag handle:3 reason:40
08-15 13:25:01.620 5426 5449 D BluetoothGatt: onClientConnectionState() - status=40 clientIf=5 device={MAC}
08-15 13:25:06.801 4736 6104 D BtGatt.GattService: clientDisconnect() - address={MAC}, connId=null
08-15 13:25:06.802 4736 5520 W bt_stack: [WARNING:bta_gattc_act.cc(1040)] bta_gattc_conn_cback: cif=5 connected=0 conn_id=0x0005 reason=0x0100
08-15 13:25:10.085 5426 5449 D BluetoothGatt: onSearchComplete() = Device={MAC} Status=0
08-15 13:25:11.000 5426 5449 D BluetoothGatt: onCharacteristicChanged() value=NOTIFICATION_SECRET
08-15 13:25:11.100 5426 5449 D BtGatt.GattService: PAIRING_KEY_SECRET
'''
        result = bt.project_logcat(text)
        self.assertEqual(len(result['events']), 7)
        self.assertEqual(result['events'][3]['client_id'], 5)
        self.assertEqual(result['events'][3]['status'], 40)
        self.assertIsNone(result['events'][4]['connection_id'])
        self.assertEqual(result['events'][5]['reason'], 256)
        self.assert_private_absent(result)

    def test_private_suffix_or_fake_scalar_is_rejected(self):
        text = f'''08-15 10:00:00.000 1 2 D BluetoothGatt: onClientConnectionState() - status=0 clientIf=6 device={MAC} name=PERSON_SECRET status=12345
08-15 10:00:00.001 1 2 D BluetoothGatt: close() key=PAIRING_KEY_SECRET
08-15 10:00:00.002 1 2 D BluetoothGatt: onClientConnectionState() - status=79991234567 clientIf=6 device={MAC}
08-15 10:00:00.003 1 2 D BluetoothGatt: registerForNotification() - address=PERSON_SECRET enable: true
'''
        result = bt.project_logcat(text)
        self.assertEqual(result['events'], [])
        self.assertEqual(result['counts']['dropped_lines'], 4)
        self.assert_private_absent(result)

    def test_valid_log_with_no_relevant_events_is_supported(self):
        result = bt.project_logcat('08-15 13:46:20.264 1 2 D BluetoothManagerService: unrelated')
        self.assertFalse(result['unsupported_format'])
        self.assertTrue(result['no_selected_events'])
        self.assertEqual(result['events'], [])

    def test_unsupported_format_does_not_echo(self):
        for projector in (bt.project_dumpsys, bt.project_logcat):
            result = projector('PERSON_SECRET +79991234567 NOTIFICATION_SECRET PAIRING_KEY_SECRET')
            self.assertTrue(result['unsupported_format'])
            self.assert_private_absent(result)

if __name__ == '__main__':
    unittest.main()
