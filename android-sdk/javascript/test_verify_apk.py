import tempfile
import unittest
import zipfile
from pathlib import Path
from verify_apk import verify_metadata

class MetadataTest(unittest.TestCase):
    def test_missing_old_metadata_is_rejected_and_new_metadata_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'app.apk'
            for content, valid in [(b'publishGlanceboard', False), (b'registerGlanceboardWidgets\0publishGlanceboardWidget', True)]:
                with zipfile.ZipFile(apk, 'w') as archive:
                    archive.writestr('assets/metadata/treeStringsStream.dat', content)
                if valid:
                    verify_metadata(apk)
                else:
                    with self.assertRaises(ValueError): verify_metadata(apk)
            with self.assertRaises(ValueError): verify_metadata(apk, ['futureMethod'])

if __name__ == '__main__': unittest.main()
