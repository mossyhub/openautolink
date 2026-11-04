"""Keep direct CarPlay origin credits in public documentation and source notices."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
ATTRIBUTION = "Based on LIVI by Lasse Heitgres (https://github.com/f-io/LIVI)"
PROVENANCE = ROOT / "carplay-lab/src/main/java/com/shilapi/xcertplay/airplay/PROVENANCE.md"


class CarPlayAttributionTest(unittest.TestCase):
    def test_exact_livi_credit_is_retained_in_documentation_and_notices(self):
        for path in (ROOT / "README.md", ROOT / "NOTICE", PROVENANCE):
            with self.subTest(path=path.relative_to(ROOT)):
                self.assertIn(ATTRIBUTION, path.read_text())

    def test_intermediate_projects_remain_credited(self):
        for path in (ROOT / "README.md", ROOT / "NOTICE"):
            with self.subTest(path=path.name):
                text = path.read_text()
                self.assertIn("https://github.com/shilapi/xcertplay", text)
                self.assertIn("https://github.com/shihabal3amri/DiPlay", text)

    def test_documentation_notice_links_resolve(self):
        for path in (ROOT / "README.md", PROVENANCE):
            with self.subTest(path=path.relative_to(ROOT)):
                link = re.search(r"\[CarPlay attribution notices\]\(([^)]+)\)", path.read_text())
                self.assertIsNotNone(link)
                assert link is not None
                self.assertEqual(ROOT / "NOTICE", (path.parent / link.group(1)).resolve())
                self.assertTrue((path.parent / link.group(1)).is_file())


if __name__ == "__main__":
    unittest.main()
