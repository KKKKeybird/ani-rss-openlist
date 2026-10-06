import importlib.util
import json
import pathlib
import unittest
from unittest.mock import patch

path = pathlib.Path(__file__).with_name('codex_cloud_sync.py')
spec = importlib.util.spec_from_file_location('cloud_sync', path)
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class CloudApprovalTests(unittest.TestCase):
    def comment(self, **overrides):
        result = dict(request_id='request-1', reviewed_head='head-1', reviewed_base='base-1',
                      approved=True, remaining_findings=[], model='gpt-6.1-sol', reasoning_effort='medium',
                      summary='Reviewed and verified')
        result.update(overrides)
        return dict(user={'login': sync.BOT}, created_at='2026-10-06T01:00:01Z',
                    body=sync.RESULT + '\n```json\n' + json.dumps(result) + '\n```')

    def requests(self):
        return {'request-1': {'created_at': '2026-10-06T01:00:00Z'}}

    def approve(self, comment):
        return sync.approved_result(comment, self.requests(), 'head-1', 'base-1')

    def test_current_head_and_base_with_explicit_cloud_approval(self):
        self.assertIsNotNone(self.approve(self.comment()))

    def test_regular_user_cannot_spoof_cloud_approval(self):
        comment = self.comment()
        comment['user']['login'] = 'someone-else'
        self.assertIsNone(self.approve(comment))

    def test_stale_head_base_or_request_never_approves(self):
        for fields in [dict(reviewed_head='old-head'), dict(reviewed_base='old-base'),
                       dict(request_id='unknown')]:
            with self.subTest(fields=fields):
                self.assertIsNone(self.approve(self.comment(**fields)))

    def test_findings_or_non_boolean_approval_block_merge(self):
        for fields in [dict(remaining_findings=['broken collection']), dict(approved=False),
                       dict(approved='true'), dict(approved=1), dict(remaining_findings=None)]:
            with self.subTest(fields=fields):
                self.assertIsNone(self.approve(self.comment(**fields)))

    def test_plain_review_and_old_response_do_not_approve(self):
        comment = self.comment()
        comment['body'] = 'Looks good, all tests pass.'
        self.assertIsNone(self.approve(comment))
        comment = self.comment()
        comment['created_at'] = '2026-10-06T00:59:59Z'
        self.assertIsNone(self.approve(comment))

    def test_request_must_originate_from_authorized_cloud_account(self):
        body = sync.REQUEST + json.dumps(dict(id='id', head='head', base='base')) + ' -->'
        self.assertIsNone(sync.parse_request(dict(user={'login': 'contributor'}, body=body)))
        self.assertIsNone(sync.parse_request(dict(user={'login': 'github-actions[bot]'}, body=body)))
        self.assertIsNotNone(sync.parse_request(dict(user={'login': 'KKKKeybird'}, body=body)))



if __name__ == '__main__':
    unittest.main()

class CoordinatorTests(CloudApprovalTests):
    def run_process(self, runs=None, comments=None, changed_head=False, base_current=True):
        repo = 'KKKKeybird/ani-rss-openlist'
        pr = dict(number=2, state='open', base={'ref': 'main'},
                  head={'sha': 'head-1', 'ref': 'sync/upstream-v3.2.40', 'repo': {'full_name': repo}},
                  labels=[{'name': 'upstream-sync'}], draft=True, mergeable=True)
        request = dict(id='request-1', head='head-1', base='base-1')
        request_comment = dict(user={'login': 'KKKKeybird'}, created_at='2026-10-06T01:00:00Z',
                               body=sync.REQUEST + json.dumps(request) + ' -->')
        pulls_calls = 0
        def response(_repo, path, *args):
            nonlocal pulls_calls
            if path == 'pulls/2':
                pulls_calls += 1
                if changed_head and pulls_calls > 1:
                    return dict(pr, head=dict(pr['head'], sha='head-2'))
                return pr
            if path == 'git/ref/heads/main':
                return {'object': {'sha': 'base-1'}}
            if path == 'compare/base-1...head-1':
                return {'merge_base_commit': {'sha': 'base-1' if base_current else 'old-base'}}
            if path.startswith('actions/workflows/'):
                default = dict(id=1, head_sha='head-1', head_branch=pr['head']['ref'],
                               event='workflow_dispatch', status='completed', conclusion='success')
                return {'workflow_runs': [default] if runs is None else runs}
            if path == 'issues/2/comments':
                return {'id': 42}
            if path == 'pulls/2/merge':
                return {'merged': True, 'sha': 'merge-sha'}
            raise AssertionError(path)
        with patch.object(sync, 'api', side_effect=response) as api, \
             patch.object(sync, 'pages', return_value=[request_comment] + (comments or [self.comment()])), \
             patch.object(sync, 'gh') as gh:
            sync.process(repo, 2)
            return api.call_args_list, gh.call_args_list, []

    def test_merge_uses_exact_reviewed_head_after_independent_verification(self):
        calls, commands, _ = self.run_process()
        merge = next(call for call in calls if call.args[1] == 'pulls/2/merge')
        self.assertIn('sha=head-1', merge.args)
        self.assertTrue(any(call.args[:2] == ('pr', 'ready') for call in commands))

    def test_old_head_ci_or_pending_run_cannot_authorize_merge(self):
        for runs in [[], [dict(id=1, head_sha='old-head', head_branch='sync/upstream-v3.2.40',
                              event='workflow_dispatch', status='completed', conclusion='success')],
                     [dict(id=1, head_sha='head-1', head_branch='sync/upstream-v3.2.40',
                           event='workflow_dispatch', status='in_progress', conclusion=None)]]:
            calls, _, _ = self.run_process(runs=runs)
            self.assertFalse(any(call.args[1] == 'pulls/2/merge' for call in calls))

    def test_latest_failed_ci_flags_cloud_repair_instead_of_merging(self):
        runs = [dict(id=2, head_sha='head-1', head_branch='sync/upstream-v3.2.40',
                     event='workflow_dispatch', status='completed', conclusion='failure', updated_at='2026-10-06T01:00:02Z', html_url='https://github.com/run/2'),
                dict(id=1, head_sha='head-1', head_branch='sync/upstream-v3.2.40',
                     event='workflow_dispatch', status='completed', conclusion='success')]
        calls, _, tasks = self.run_process(runs=runs)
        self.assertFalse(any(call.args[1] == 'pulls/2/merge' for call in calls))
        self.assertTrue(any(call.args[1] == 'issues/2/comments' for call in calls))

    def test_changed_head_or_old_base_blocks_merge(self):
        for fields in [dict(changed_head=True), dict(base_current=False)]:
            calls, _, _ = self.run_process(**fields)
            self.assertFalse(any(call.args[1] == 'pulls/2/merge' for call in calls))

    def test_newer_denial_invalidates_previous_approval(self):
        denied = self.comment(approved=False, remaining_findings=['OpenList unavailable'])
        denied['created_at'] = '2026-10-06T01:00:02Z'
        calls, _, _ = self.run_process(comments=[self.comment(), denied])
        self.assertFalse(any(call.args[1] == 'pulls/2/merge' for call in calls))
