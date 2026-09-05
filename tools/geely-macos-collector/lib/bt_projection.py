"""Pure, fail-closed projections of KX11 Bluetooth diagnostic text.

Callers keep input only in memory. Outputs contain no source lines, device identifiers,
UUIDs, call/notification content, key material, or arbitrary diagnostic messages.
Only known package identities and exact event/field grammars are emitted.
"""
from __future__ import annotations

import re
from collections import Counter

KNOWN_PACKAGES = frozenset({
    'android', 'ru.natro.statuswidget', 'org.astpepper.hwgps',
    'ru.yandex.yandexnavi', 'com.android.bluetooth', 'com.ts.dm.service',
    'ecarx.powersomeip.service', 'com.ecarx.btphone',
})
ADAPTER_STATES = frozenset({
    'ON', 'OFF', 'TURNING_ON', 'TURNING_OFF', 'BLE_ON',
    'BLE_TURNING_ON', 'BLE_TURNING_OFF',
})
PROFILES = frozenset({
    'GattService', 'A2dpSinkService', 'HeadsetClientService',
    'AvrcpControllerService', 'PbapClientService', 'MapClientService',
    'HidHostService', 'A2dpService', 'HeadsetService', 'HearingAidService',
    'PanService', 'SapService', 'MapService', 'PbapService',
})
LOGCAT_TAGS = (
    'BluetoothManagerService', 'BluetoothGatt', 'BtGatt.GattService',
    'bt_btm_sec', 'bt_stack', 'HeadsetClientStateMachine',
    'A2dpSinkStateMachine',
)
# High-volume bt_btm can be added explicitly when native disconnect commands are needed.
OPTIONAL_LOGCAT_TAGS = ('bt_btm',)
_TIME = r'[0-1][0-9]-[0-3][0-9] [0-2][0-9]:[0-5][0-9]:[0-5][0-9]'
_THREAD = re.compile(r'^(' + _TIME + r'\.[0-9]{3,6})\s+([0-9]+)\s+([0-9]+)\s+([VDIWEF])\s+([^:]+?)(?=:\s)(?::\s*)(.*)$')
_PACKAGE = re.compile(r'^\s{2,}([a-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+)\s*(?:\((Registered|Filtered)\))?\s*$')
_GATT_OPS = frozenset({
    'connect', 'close', 'unregisterApp', 'registerClient',
    'onClientRegistered', 'clientConnect', 'clientDisconnect',
    'unregisterClient', 'onClientConnectionState', 'discoverServices',
    'onSearchCompleted', 'onSearchComplete', 'configureMTU',
    'onConfigureMTU', 'requestConnectionPriority', 'onConnectionUpdated',
    'registerForNotification', 'onScanFilterParamsConfigured',
})
_ADDR = r'(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}'
_UUID = r'[0-9A-Fa-f]{8}(?:-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}'
_INT = r'[0-9]{1,5}'
# Each full regex discards validated identifiers and captures only approved scalar fields.
_GATT_FORMATS = (
    ('connect', r'connect\(\) - device: ' + _ADDR + r', auto: (?P<auto>true|false)'),
    ('close', r'close\(\)'),
    ('unregisterApp', r'unregisterApp\(\) - mClientIf=(?P<client_id>' + _INT + ')'),
    ('unregisterClient', r'unregisterClient\(\) - clientIf=(?P<client_id>' + _INT + ')'),
    ('registerClient', r'registerClient\(\) - UUID=' + _UUID),
    ('onClientRegistered', r'onClientRegistered\(\) - status=(?P<status>' + _INT + r') clientIf=(?P<client_id>' + _INT + ')'),
    ('onClientRegistered', r'onClientRegistered\(\) - UUID=' + _UUID + r', clientIf=(?P<client_id>' + _INT + ')'),
    ('clientConnect', r'clientConnect\(\) - address=' + _ADDR + r', isDirect=(?P<is_direct>true|false), opportunistic=(?P<opportunistic>true|false), phy=(?P<phy>[1-7])'),
    ('clientDisconnect', r'clientDisconnect\(\) - address=' + _ADDR + r', connId=(?P<connection_id>' + _INT + '|null)'),
    ('onClientConnectionState', r'onClientConnectionState\(\) - status=(?P<status>' + _INT + r') clientIf=(?P<client_id>' + _INT + r') device=' + _ADDR),
    ('discoverServices', r'discoverServices\(\) - device: ' + _ADDR),
    ('discoverServices', r'discoverServices\(\) - address=' + _ADDR + r', connId=(?P<connection_id>' + _INT + ')'),
    ('onSearchCompleted', r'onSearchCompleted\(\) - connId=(?P<connection_id>' + _INT + r'), status=(?P<status>' + _INT + ')'),
    ('onSearchComplete', r'onSearchComplete\(\) = Device=' + _ADDR + r' Status=(?P<status>' + _INT + ')'),
    ('configureMTU', r'configureMTU\(\) - device: ' + _ADDR + r' mtu: (?P<mtu>' + _INT + ')'),
    ('configureMTU', r'configureMTU\(\) - address=' + _ADDR + r' mtu=(?P<mtu>' + _INT + ')'),
    ('onConfigureMTU', r'onConfigureMTU\(\) address=' + _ADDR + r', status=(?P<status>' + _INT + r'), mtu=(?P<mtu>' + _INT + ')'),
    ('onConfigureMTU', r'onConfigureMTU\(\) - Device=' + _ADDR + r' mtu=(?P<mtu>' + _INT + r') status=(?P<status>' + _INT + ')'),
    ('requestConnectionPriority', r'requestConnectionPriority\(\) - params: (?P<priority>[0-2])'),
    ('onConnectionUpdated', r'onConnectionUpdated\(\) - Device=' + _ADDR + r' interval=(?P<interval>' + _INT + r') latency=(?P<latency>' + _INT + r') timeout=(?P<timeout>' + _INT + r') status=(?P<status>' + _INT + ')'),
    ('registerForNotification', r'registerForNotification\(\) - address=' + _ADDR + r' enable: (?P<enable>true|false)'),
    ('onScanFilterParamsConfigured', r'onScanFilterParamsConfigured\(\) - clientIf=(?P<client_id>' + _INT + r'), status=(?P<status>' + _INT + r'), action=(?P<action>' + _INT + r'), availableSpace=(?P<available_space>' + _INT + ')'),
)
_GATT_PATTERNS = tuple((op, re.compile(pattern)) for op, pattern in _GATT_FORMATS)


def _gatt_event(message: str):
    for event, pattern in _GATT_PATTERNS:
        match = pattern.fullmatch(message)
        if not match:
            continue
        fields = {'event': event, 'namespace': 'android_gatt'}
        for key, value in match.groupdict().items():
            if value in ('true', 'false'):
                fields[key] = value == 'true'
            elif value == 'null':
                fields[key] = None
            else:
                number = int(value)
                if number > 65535:
                    return None
                fields[key] = number
        return fields
    return None


def _package(value: str) -> str:
    return value if value in KNOWN_PACKAGES else 'other_application'


def _count_result(lines: list[str], accepted: set[int], unsupported: int) -> dict:
    return {
        'input_lines': len(lines), 'accepted_lines': len(accepted),
        'dropped_lines': len(lines) - len(accepted),
        'unsupported_format_lines': unsupported,
        'raw_text_retained': False,
    }


def project_dumpsys(text: str) -> dict:
    """Project status and GATT registry, preserving scanner/client/server namespaces."""
    lines = text.splitlines()
    result = {'schema_version': 1, 'kind': 'bluetooth_manager_projection',
              'adapter': {}, 'enable_history': [], 'profiles': [], 'gatt_entries': []}
    accepted: set[int] = set()
    section = None
    entry = None
    unsupported = 0
    for no, line in enumerate(lines, 1):
        stripped = line.strip()
        match = re.fullmatch(r'enabled: (true|false)', stripped)
        if match and section is None:
            result['adapter']['enabled'] = match[1] == 'true'; accepted.add(no); continue
        match = re.fullmatch(r'state: ([A-Z_]+)', stripped)
        if match and match[1] in ADAPTER_STATES and section is None:
            result['adapter']['state'] = match[1]; accepted.add(no); continue
        match = re.fullmatch(r'time since enabled: ([0-9]{2,4}:[0-5][0-9]:[0-5][0-9]\.[0-9]{3})', stripped)
        if match:
            result['adapter']['time_since_enabled'] = match[1]; accepted.add(no); continue
        match = re.fullmatch(r'Bluetooth crashed ([0-9]{1,7}) times', stripped)
        if match:
            result['adapter']['crash_count'] = int(match[1]); accepted.add(no); continue
        match = re.fullmatch(r'(' + _TIME + r')\s+(Enabled|Disabled)\s+due to (APPLICATION_REQUEST|SYSTEM_BOOT|RESTARTED|AIRPLANE_MODE|DISALLOWED|CRASH|RESTORE_USER_SETTING) by ([A-Za-z0-9_.]+)', stripped)
        if match:
            result['enable_history'].append({'line': no, 'time': match[1],
                'enabled': match[2] == 'Enabled', 'reason': match[3],
                'caller_package': _package(match[4])})
            accepted.add(no); continue
        match = re.fullmatch(r'Profile: ([A-Za-z]+)', stripped)
        if match:
            section = None; entry = None
            if match[1] in PROFILES:
                result['profiles'].append({'line': no, 'profile': match[1]}); accepted.add(no)
            else: unsupported += 1
            continue
        match = re.fullmatch(r'GATT (Scanner|Client|Server|Handle) Map', stripped)
        if match:
            section = match[1].lower(); entry = None; accepted.add(no); continue
        if section not in {'scanner', 'client', 'server'}:
            continue
        match = _PACKAGE.fullmatch(line)
        if match:
            entry = {'line': no, 'namespace': section,
                'package': _package(match[1]), 'registered': match[2] == 'Registered',
                'filtered': match[2] == 'Filtered'}
            result['gatt_entries'].append(entry); accepted.add(no); continue
        if entry is None:
            continue
        match = re.fullmatch(r'Application ID\s*:\s*([0-9]{1,5})', stripped)
        if match:
            entry['application_id'] = int(match[1]); accepted.add(no); continue
        match = re.fullmatch(r'Connections:\s*([0-9]{1,5})', stripped)
        if match:
            entry['connections'] = int(match[1]); accepted.add(no); continue
        match = re.fullmatch(r'LE scans \(started/stopped\)\s*:\s*([0-9]{1,7}) / ([0-9]{1,7})', stripped)
        if match:
            entry['scans_started'] = int(match[1]); entry['scans_stopped'] = int(match[2]); accepted.add(no)
    result['counts'] = _count_result(lines, accepted, unsupported)
    result['unsupported_format'] = not bool(result['adapter'] or result['profiles'] or result['gatt_entries'])
    return result


def project_logcat(text: str) -> dict:
    """Project selected lifecycle events; every output field is fixed, numeric or enum."""
    lines = text.splitlines(); events = []; accepted: set[int] = set(); unsupported = 0; parsed_lines = 0
    for no, line in enumerate(lines, 1):
        parsed = _THREAD.fullmatch(line)
        if not parsed:
            if line and not line.startswith('--------- beginning of '): unsupported += 1
            continue
        parsed_lines += 1
        time, pid, tid, level, tag, msg = parsed.groups(); tag = tag.strip()
        if tag not in LOGCAT_TAGS + OPTIONAL_LOGCAT_TAGS:
            continue
        data = None
        if tag in {'BluetoothGatt', 'BtGatt.GattService'}:
            data = _gatt_event(msg)
            if msg == 'Exception: android.os.DeadObjectException':
                data = {'event': 'dead_object_exception', 'namespace': 'android_gatt_binder'}
        elif tag == 'BluetoothManagerService':
            match = re.match(r'^enable\(([A-Za-z0-9_.]+)\):.*\bmState = ([A-Z_]+)$', msg)
            if match and match[2] in ADAPTER_STATES:
                data = {'event': 'enable_request', 'namespace': 'android_bluetooth_manager',
                    'caller_package': _package(match[1]), 'state': match[2]}
            elif msg == 'waitForOnOff time out':
                data = {'event': 'wait_for_on_off_timeout', 'namespace': 'android_bluetooth_manager'}
            else:
                match = re.fullmatch(r'MESSAGE_BLUETOOTH_STATE_CHANGE: ([A-Z_]+) > ([A-Z_]+)', msg)
                if match and match[1] in ADAPTER_STATES and match[2] in ADAPTER_STATES:
                    data = {'event': 'adapter_state_change', 'namespace': 'android_bluetooth_manager',
                        'from_state': match[1], 'to_state': match[2]}
        elif tag == 'bt_btm_sec':
            match = re.fullmatch(r'btm_sec_disconnected clearing pending flag handle:([0-9]{1,5}) reason:([0-9]{1,5})', msg)
            if match:
                data = {'event': 'link_disconnected', 'namespace': 'native_btm',
                    'handle': int(match[1]), 'reason': int(match[2])}
        elif tag == 'bt_btm':
            match = re.fullmatch(r'(?:v[0-9.]+[A-Za-z]?:)?btm_sec_send_hci_disconnect:\s+handle:0x([0-9a-fA-F]{1,4}), reason=0x([0-9a-fA-F]{1,4})', msg)
            if match:
                data = {'event': 'send_disconnect', 'namespace': 'native_hci_command',
                    'handle': int(match[1],16), 'reason': int(match[2],16)}
        elif tag == 'bt_stack':
            match = re.fullmatch(r'\[WARNING:bta_gattc_act\.cc\([0-9]+\)\] bta_gattc_conn_cback: cif=([0-9]{1,5}) connected=([01]) conn_id=0x([0-9a-fA-F]{1,4}) reason=0x([0-9a-fA-F]{1,4})', msg)
            if match:
                data = {'event': 'gatt_connection_callback', 'namespace': 'native_gatt',
                    'client_id': int(match[1]), 'connected': bool(int(match[2])),
                    'connection_id': int(match[3],16), 'reason': int(match[4],16)}
        # Profile tags are intentionally not parsed yet: their free-form messages include
        # personal device and call fields. Availability in LOGCAT_TAGS is not a claim of support.
        if data is not None:
            events.append({'line': no, 'time': time, 'pid': int(pid), 'tid': int(tid),
                'tag': tag, 'severity': level, **data}); accepted.add(no)
    return {'schema_version': 1, 'kind': 'bluetooth_logcat_projection', 'events': events,
        'counts': _count_result(lines, accepted, unsupported),
        'event_counts': dict(Counter(e['event'] for e in events)),
        'parsed_threadtime_lines': parsed_lines,
        'no_selected_events': not bool(events),
        'unsupported_format': bool(lines) and parsed_lines == 0}
