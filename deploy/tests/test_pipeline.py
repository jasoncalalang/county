"""Pipeline and image-context tests: plain text checks of files that cannot be executed here.

Run from the repository root: python3 -m unittest discover deploy/tests
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path):
    assert (ROOT / path).is_file(), f"{path} does not exist"
    return (ROOT / path).read_text()


def dockerignore_patterns():
    text = read(".dockerignore")
    return [line.strip() for line in text.splitlines() if line.strip() and not line.startswith("#")]


class DockerignoreTest(unittest.TestCase):
    """I3: the build context keeps secrets and build output out of the images."""

    def test_git_history_is_excluded(self):
        self.assertIn(".git", dockerignore_patterns())

    def test_env_files_are_excluded_except_the_example(self):
        patterns = dockerignore_patterns()
        self.assertIn("**/.env*", patterns)
        self.assertIn("!**/.env.example", patterns)
        self.assertLess(patterns.index("**/.env*"), patterns.index("!**/.env.example"))

    def test_dependencies_and_build_output_are_excluded(self):
        patterns = dockerignore_patterns()
        for pattern in ("**/node_modules", "**/build", "**/.gradle", "**/.next", "**/test-results"):
            with self.subTest(pattern=pattern):
                self.assertIn(pattern, patterns)

    def test_nothing_the_images_build_from_is_excluded(self):
        patterns = dockerignore_patterns()
        for source in ("api", "web", "api/src", "web/app", "web/lib", "web/package-lock.json", "api/gradlew"):
            with self.subTest(source=source):
                self.assertNotIn(source, patterns)
                self.assertNotIn(f"{source}/**", patterns)


if __name__ == "__main__":
    unittest.main()
