import unittest

from server import is_opencode_process


class ProcessDetectionTest(unittest.TestCase):
    def test_accepts_opencode_binary(self):
        self.assertTrue(is_opencode_process("opencode", "opencode"))
        self.assertTrue(is_opencode_process("opencode-go", "opencode-go"))

    def test_rejects_shell_that_only_mentions_opencode(self):
        self.assertFalse(is_opencode_process("bash", "bash"))
        self.assertFalse(is_opencode_process("python3", "python3"))


if __name__ == "__main__":
    unittest.main()
