"""Source UI contracts only; not rendered Compose or physical phone proof.

Run with python3 app/src/test/independent_connection_ui_contract.py -v.
These can run during backend development without replacing production APIs.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
UI = ROOT / "app/src/main/java/com/openautolink/app/ui"


def source(path):
    return (UI / path).read_text()


def function(text, name):
    start = text.index(f"fun {name}(")
    # Top-level Compose declarations delimit these UI functions.
    end = re.search(r"(?m)^(?:@Composable|private fun |internal fun |fun )", text[start + 4:])
    return text[start:start + 4 + end.start()] if end else text[start:]


class IndependentConnectionUiContract(unittest.TestCase):
    def test_protocol_modes_are_independent_without_implicit_enable(self):
        connection = function(source("settings/SettingsScreen.kt"), "ConnectionTab")
        carplay = function(source("settings/CarPlaySettings.kt"), "CarPlaySettingsSection")
        self.assertNotIn('SectionHeader("Connection mode")', connection)
        self.assertIn('Text("Android Auto connection"', connection)
        self.assertIn('AppPreferences.DIRECT_TRANSPORT_USB to "USB"', connection)
        self.assertIn('AppPreferences.DIRECT_TRANSPORT_WPP to "Wireless"', connection)
        self.assertIn('viewModel.updateDirectTransport(mode)', connection)
        self.assertIn('Text("CarPlay connection"', carplay)
        self.assertIn('listOf("usb" to "USB", "wireless" to "Wireless")', carplay)
        self.assertIn('selected = settings.connectionMode == mode', carplay)
        self.assertIn('viewModel.updateCarPlayDraft { it.copy(connectionMode = mode) }', carplay)
        self.assertLess(carplay.index('Text("CarPlay connection"'), carplay.index('if (!settings.enabled) return'))
        self.assertNotIn('copy(enabled =', carplay)
        self.assertNotIn('updateDirectTransport', carplay)
        self.assertNotIn('saveCarPlaySettings', carplay)


    def test_wired_carplay_does_not_present_wireless_fields_as_requirements(self):
        carplay = function(source("settings/CarPlaySettings.kt"), "CarPlaySettingsSection")
        paired = carplay.index('Text("Paired iPhone"')
        self.assertTrue('if (draft.connectionMode == "wireless")' in carplay,
                        'Wireless-only pairing must not be required for USB')
        wireless_gate = carplay.index('if (draft.connectionMode == "wireless")')
        self.assertLess(wireless_gate, paired)
        self.assertIn('if (advanced && draft.connectionMode == "wireless")', carplay)
        self.assertIn('wireless CarPlay is unverified in the car', carplay)
        self.assertIn('Selecting a connection does not enable CarPlay', carplay)
        connection = function(source("settings/SettingsScreen.kt"), "ConnectionTab")
        self.assertNotIn('DIRECT_TRANSPORT_HOTSPOT', connection)
        self.assertNotIn('"Car Hotspot"', connection)
        self.assertNotIn('"Phone Hotspot"', connection)
        self.assertIn('temporarily take over after you confirm; saved modes stay unchanged', connection)

    def test_usb_confirmation_has_priority_over_other_connection_modals(self):
        projection = function(source("projection/ProjectionScreen.kt"), "ProjectionScreen")
        self.assertTrue('if (!showSettings && !showDiagnostics && usbHotPlugPrompt == null)' in projection,
                        'CarPlay failure dialog must not cover a pending USB confirmation')
        self.assertTrue('if (usbHotPlugPrompt == null && UsbPickerVisibility.shouldShow(' in projection,
                        'Existing USB picker must not compete with the confirmation')

    def test_usb_confirmation_is_common_to_both_projection_branches(self):
        screen = source("projection/ProjectionScreen.kt")
        projection = function(screen, "ProjectionScreen")
        self.assertIn('viewModel.usbHotPlugPrompt.collectAsStateWithLifecycle()', projection)
        self.assertIn('if (!showSettings && !showDiagnostics && !showCarPlaySafeEditor)', projection)
        self.assertEqual(1, projection.count('UsbHotPlugSwitchDialog('))
        self.assertLess(projection.index('UsbHotPlugSwitchDialog('), projection.index('if (carPlayState.selected)'))
        self.assertIn('viewModel.acceptUsbHotPlug(prompt.generation)', projection)
        self.assertIn('viewModel.stayOnCurrentProjection(prompt.generation)', projection)
        dialog = function(screen, "UsbHotPlugSwitchDialog")
        self.assertIn('prompt: com.openautolink.app.carplay.UsbHotPlugPrompt', dialog)
        self.assertIn('onDismissRequest = onStay', dialog)
        self.assertIn('onClick = onSwitch', dialog)
        self.assertIn('onClick = onStay', dialog)
        self.assertIn('Text("Switch")', dialog)
        self.assertIn('Text("Stay here")', dialog)
        self.assertIn('${prompt.protocolLabel} for this connection?', dialog)
        for forbidden in ('updateDirectTransport', 'updateCarPlay', 'setDirectTransport', '.launch(', 'UsbConnectionManager', 'returnToAndroidAuto'):
            self.assertNotIn(forbidden, dialog)


if __name__ == "__main__":
    unittest.main()
