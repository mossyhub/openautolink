from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/refresh-website-template.yml'


class WorkflowTests(unittest.TestCase):
    def test_ci_runs_new_tests_without_deployment_secret(self):
        text = (ROOT / '.github/workflows/ci.yml').read_text()
        self.assertIn("'website-release/**'", text)
        self.assertIn("'.github/workflows/refresh-website-template.yml'", text)
        self.assertIn('python3 -I -S -m unittest discover -s website-release/tests -v', text)
        self.assertNotIn('OAL_WEBSITE_DEPLOYMENT_TOKEN', text)

    def test_secret_job_only_own_success_event_and_trusted_default_checkout(self):
        self.assertTrue(WORKFLOW.exists(), 'missing durable refresh workflow')
        text = WORKFLOW.read_text()
        self.assertIn('workflows: [Publish personalization template]', text)
        self.assertIn('types: [completed]', text)
        self.assertIn('workflow_dispatch:', text)
        self.assertNotIn('pull_request:', text)
        self.assertNotIn('pull_request_target:', text)
        self.assertIn("github.event.workflow_run.conclusion == 'success'", text)
        self.assertIn("github.event.workflow_run.head_repository.full_name == github.repository", text)
        self.assertIn('ref: ${{ github.event.repository.default_branch }}', text)
        self.assertIn('persist-credentials: false', text)
        self.assertIn('group: oal-website-template-deployment', text)
        self.assertIn('cancel-in-progress: false', text)
        self.assertIn('GH_TOKEN: ${{ github.token }}', text)
        self.assertEqual(text.count('secrets.OAL_WEBSITE_DEPLOYMENT_TOKEN'), 1)
        self.assertIn('@azure/static-web-apps-cli@2.0.10', text)
        self.assertIn('--app-name oal-web-preview', text)
        self.assertIn('--subscription-id de1ebbeb-11f8-4375-bb7f-182f7d30c6a2', text)
        self.assertIn('--resource-group oal-web-preview', text)
        self.assertNotIn('actions/download-artifact', text)
        self.assertLess(text.index('refresh.py recheck'), text.index('swa\" deploy'))
        self.assertGreater(text.index('refresh.py readback'), text.index('swa\" deploy'))


if __name__ == '__main__':
    unittest.main()
