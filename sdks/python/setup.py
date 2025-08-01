#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""Apache Beam SDK for Python setup file."""
import glob
import logging
import os
import shutil
import subprocess
import sys
import warnings
# Pylint and isort disagree here.
# pylint: disable=ungrouped-imports
from importlib.metadata import PackageNotFoundError
from importlib.metadata import distribution
from pathlib import Path

# pylint: disable=ungrouped-imports
import setuptools
from packaging.version import parse
from setuptools import Command

# pylint: disable=wrong-import-order
# It is recommended to import setuptools prior to importing distutils to avoid
# using legacy behavior from distutils.
# https://setuptools.readthedocs.io/en/latest/history.html#v48-0-0
from distutils.errors import DistutilsError  # isort:skip


def to_filename(name: str) -> str:
  return name.replace('-', '_')


def normalize_path(filename):
  return os.path.normcase(os.path.realpath(os.path.normpath(filename)))


class mypy(Command):
  user_options = []

  def initialize_options(self):
    """Abstract method that is required to be overwritten"""

  def finalize_options(self):
    """Abstract method that is required to be overwritten"""

  def get_project_path(self):
    self.run_command('egg_info')

    # Build extensions in-place
    self.reinitialize_command('build_ext', inplace=1)
    self.run_command('build_ext')

    ei_cmd = self.get_finalized_command("egg_info")

    project_path = normalize_path(ei_cmd.egg_base)
    return os.path.join(project_path, to_filename(ei_cmd.egg_name))

  def run(self):
    args = ['mypy', self.get_project_path()]
    result = subprocess.call(args)
    if result != 0:
      raise DistutilsError("mypy exited with status %d" % result)


def get_version():
  global_names = {}
  exec(  # pylint: disable=exec-used
      open(os.path.join(
          os.path.dirname(os.path.abspath(__file__)),
          'apache_beam/version.py')
          ).read(),
      global_names
  )
  return global_names['__version__']


PACKAGE_NAME = 'apache-beam'
PACKAGE_VERSION = get_version()
PACKAGE_DESCRIPTION = 'Apache Beam SDK for Python'
PACKAGE_URL = 'https://beam.apache.org'
PACKAGE_DOWNLOAD_URL = 'https://pypi.python.org/pypi/apache-beam'
PACKAGE_AUTHOR = 'Apache Software Foundation'
PACKAGE_EMAIL = 'dev@beam.apache.org'
PACKAGE_KEYWORDS = 'apache beam'

RECOMMENDED_MIN_PIP_VERSION = '19.3.0'
try:
  _PIP_VERSION = distribution('pip').version
  if parse(_PIP_VERSION) < parse(RECOMMENDED_MIN_PIP_VERSION):
    warnings.warn(
        "You are using version {0} of pip. " \
        "However, the recommended min version is {1}.".format(
            _PIP_VERSION, RECOMMENDED_MIN_PIP_VERSION
        )
    )
except PackageNotFoundError:
  # Do nothing if pip is not found. This can happen when using `Poetry` or
  # `pipenv` package managers.
  pass

REQUIRED_CYTHON_VERSION = '3.0.0'
try:
  _CYTHON_VERSION = distribution('cython').version
  if parse(_CYTHON_VERSION) < parse(REQUIRED_CYTHON_VERSION):
    warnings.warn(
        "You are using version {0} of cython. " \
        "However, version {1} is recommended.".format(
            _CYTHON_VERSION, REQUIRED_CYTHON_VERSION
        )
    )
except PackageNotFoundError:
  # do nothing if Cython is not installed
  pass

try:
  # pylint: disable=wrong-import-position
  from Cython.Build import cythonize as cythonize0

  def cythonize(*args, **kwargs):
    import numpy
    extensions = cythonize0(*args, **kwargs)
    for e in extensions:
      e.include_dirs.append(numpy.get_include())
    return extensions
except ImportError:
  cythonize = lambda *args, **kwargs: []

# [BEAM-8181] pyarrow cannot be installed on 32-bit Windows platforms.
if sys.platform == 'win32' and sys.maxsize <= 2**32:
  pyarrow_dependency = ['']
else:
  pyarrow_dependency = [
      'pyarrow>=3.0.0,<19.0.0',
      # NOTE(https://github.com/apache/beam/issues/29392): We can remove this
      # once Beam increases the pyarrow lower bound to a version that fixes CVE.
      # (lower bound >= 14.0.1)
      'pyarrow-hotfix<1'
  ]

# Exclude pandas<=1.4.2 since it doesn't work with numpy 1.24.x.
# Exclude 1.5.0 and 1.5.1 because of
# https://github.com/pandas-dev/pandas/issues/45725
# must update the below "docs" and "test" for extras_require
dataframe_dependency = [
    'pandas>=1.4.3,!=1.5.0,!=1.5.1,<2.3',
]


def find_by_ext(root_dir, ext):
  for root, _, files in os.walk(root_dir):
    for file in files:
      if file.endswith(ext):
        yield os.path.realpath(os.path.join(root, file))


# We must generate protos after setup_requires are installed.
def generate_protos_first():
  try:
    # Pyproject toml build happens in isolated environemnts. In those envs,
    # gen_protos is unable to get imported. so we run a subprocess call.
    cwd = os.path.abspath(os.path.dirname(__file__))
    # when pip install <>.tar.gz gets called, if gen_protos.py is not available
    # in the sdist,then the proto files would have already been generated. So we
    # skip proto generation in that case.
    if not os.path.exists(os.path.join(cwd, 'gen_protos.py')):
      # make sure we already generated protos
      pb2_files = list(
          find_by_ext(
              os.path.join(cwd, 'apache_beam', 'portability', 'api'),
              '_pb2.py'))
      if not pb2_files:
        raise RuntimeError(
            'protobuf files are not generated. '
            'Please generate pb2 files')

      warnings.warn('Skipping proto generation as they are already generated.')
      return
    out = subprocess.run(
        [sys.executable, os.path.join(cwd, 'gen_protos.py'), '--no-force'],
        capture_output=True,
        check=True)
    print(out.stdout)
  except subprocess.CalledProcessError as err:
    raise RuntimeError('Could not generate protos due to error: %s', err.stderr)


def copy_tests_from_docs():
  python_root = os.path.abspath(os.path.dirname(__file__))
  docs_src = os.path.normpath(
      os.path.join(
          python_root, '../../website/www/site/content/en/documentation/sdks'))
  docs_dest = os.path.normpath(
      os.path.join(python_root, 'apache_beam/yaml/docs'))
  if os.path.exists(docs_src):
    shutil.rmtree(docs_dest, ignore_errors=True)
    os.mkdir(docs_dest)
    for path in glob.glob(os.path.join(docs_src, 'yaml*.md')):
      shutil.copy(path, docs_dest)
  else:
    warnings.warn(
        f'Could not locate yaml docs source directory {docs_src}. '
        f'Skipping copying tests from docs.')


def generate_external_transform_wrappers():
  try:
    sdk_dir = os.path.abspath(os.path.dirname(__file__))
    script_exists = os.path.exists(
        os.path.join(sdk_dir, 'gen_xlang_wrappers.py'))
    config_exists = os.path.exists(
        os.path.join(
            os.path.dirname(sdk_dir), 'standard_external_transforms.yaml'))
    # we need both the script and the standard transforms config file.
    # at build time, we don't have access to apache_beam to discover and
    # retrieve external transforms, so the config file has to already exist
    if not script_exists or not config_exists:
      generated_transforms_dir = os.path.join(
          sdk_dir, 'apache_beam', 'transforms', 'xlang')

      # if exists, this directory will have at least its __init__.py file
      if (not os.path.exists(generated_transforms_dir) or
          len(os.listdir(generated_transforms_dir)) <= 1):
        message = 'External transform wrappers have not been generated '
        if not script_exists:
          message += 'and the generation script `gen_xlang_wrappers.py`'
        if not config_exists:
          message += 'and the standard external transforms config'
        message += ' could not be found'
        raise RuntimeError(message)
      else:
        logging.info(
            'Skipping external transform wrapper generation as they '
            'are already generated.')
      return
    subprocess.run([
        sys.executable,
        os.path.join(sdk_dir, 'gen_xlang_wrappers.py'),
        '--cleanup',
        '--transforms-config-source',
        os.path.join(
            os.path.dirname(sdk_dir), 'standard_external_transforms.yaml')
    ],
                   capture_output=True,
                   check=True)
  except subprocess.CalledProcessError as err:
    raise RuntimeError(
        'Could not generate external transform wrappers due to '
        'error: %s',
        err.stderr)


def get_portability_package_data():
  files = []
  portability_dir = Path(__file__).parent / 'apache_beam' / \
                    'portability' / 'api'
  for ext in ['*.pyi', '*.yaml']:
    files.extend(
        str(p.relative_to(portability_dir.parent.parent))
        for p in portability_dir.rglob(ext))

  return files


python_requires = '>=3.9'

if sys.version_info.major == 3 and sys.version_info.minor >= 13:
  warnings.warn(
      'This version of Apache Beam has not been sufficiently tested on '
      'Python %s.%s. You may encounter bugs or missing features.' %
      (sys.version_info.major, sys.version_info.minor))

if __name__ == '__main__':
  # In order to find the tree of proto packages, the directory
  # structure must exist before the call to setuptools.find_packages()
  # executes below.
  generate_protos_first()

  generate_external_transform_wrappers()

  # These data files live elsewhere in the full Beam repository.
  copy_tests_from_docs()

  # generate cythonize extensions only if we are building a wheel or
  # building an extension or running in editable mode.
  cythonize_cmds = ('bdist_wheel', 'build_ext', 'editable_wheel')
  if any(cmd in sys.argv for cmd in cythonize_cmds):
    extensions = cythonize([
        'apache_beam/**/*.pyx',
        'apache_beam/coders/coder_impl.py',
        'apache_beam/metrics/cells.py',
        'apache_beam/metrics/execution.py',
        'apache_beam/runners/common.py',
        'apache_beam/runners/worker/logger.py',
        'apache_beam/runners/worker/opcounters.py',
        'apache_beam/runners/worker/operations.py',
        'apache_beam/transforms/cy_combiners.py',
        'apache_beam/transforms/stats.py',
        'apache_beam/utils/counters.py',
        'apache_beam/utils/windowed_value.py',
    ])
  else:
    extensions = []

  try:
    long_description = ((Path(__file__).parent /
                         "README.md").read_text(encoding='utf-8'))
  except FileNotFoundError:
    long_description = (
        'Apache Beam is a unified programming model for both batch and '
        'streaming data processing, enabling efficient execution across '
        'diverse distributed execution engines and providing extensibility '
        'points for connecting to different technologies and user '
        'communities.')

  # Keep all dependencies inlined in the setup call, otherwise Dependabot won't
  # be able to parse it.
  setuptools.setup(
      name=PACKAGE_NAME,
      version=PACKAGE_VERSION,
      description=PACKAGE_DESCRIPTION,
      long_description=long_description,
      long_description_content_type='text/markdown',
      url=PACKAGE_URL,
      download_url=PACKAGE_DOWNLOAD_URL,
      author=PACKAGE_AUTHOR,
      author_email=PACKAGE_EMAIL,
      packages=setuptools.find_packages(),
      package_data={
          'apache_beam': [
              '*/*.pyx',
              '*/*/*.pyx',
              '*/*.pxd',
              '*/*/*.pxd',
              '*/*.h',
              '*/*/*.h',
              'testing/data/*.yaml',
              'yaml/*.yaml',
              'yaml/docs/*.md',
              *get_portability_package_data()
          ]
      },
      ext_modules=extensions,
      install_requires=[
          'crcmod>=1.7,<2.0',
          'orjson>=3.9.7,<4',
          # Dill doesn't have forwards-compatibility guarantees within minor
          # version. Pickles created with a new version of dill may not unpickle
          # using older version of dill. It is best to use the same version of
          # dill on client and server, therefore list of allowed versions is
          # very narrow. See: https://github.com/uqfoundation/dill/issues/341.
          'dill>=0.3.1.1,<0.3.2',
          'fastavro>=0.23.6,<2',
          'fasteners>=0.3,<1.0',
          # TODO(https://github.com/grpc/grpc/issues/37710): Unpin grpc
          'grpcio>=1.33.1,<2,!=1.48.0,!=1.59.*,!=1.60.*,!=1.61.*,!=1.62.0,!=1.62.1,<1.66.0; python_version <= "3.12"',  # pylint: disable=line-too-long
          'grpcio>=1.67.0; python_version >= "3.13"',
          'hdfs>=2.1.0,<3.0.0',
          'httplib2>=0.8,<0.23.0',
          'jsonschema>=4.0.0,<5.0.0',
          'jsonpickle>=3.0.0,<4.0.0',
          # numpy can have breaking changes in minor versions.
          # Use a strict upper bound.
          'numpy>=1.14.3,<2.3.0',  # Update pyproject.toml as well.
          'objsize>=0.6.1,<0.8.0',
          'packaging>=22.0',
          'pymongo>=3.8.0,<5.0.0',
          'proto-plus>=1.7.1,<2',
          # 1. Use a tighter upper bound in protobuf dependency to make sure
          # the minor version at job submission
          # does not exceed the minor version at runtime.
          # To avoid depending on an old dependency, update the minor version on
          # every Beam release, see: https://github.com/apache/beam/issues/25590

          # 2. Allow latest protobuf 3 version as a courtesy to some customers.
          #
          # 3. Exclude protobuf 4 versions that leak memory, see:
          # https://github.com/apache/beam/issues/28246
          'protobuf>=3.20.3,<6.0.0.dev0,!=4.0.*,!=4.21.*,!=4.22.0,!=4.23.*,!=4.24.*',  # pylint: disable=line-too-long
          'pydot>=1.2.0,<2',
          'python-dateutil>=2.8.0,<3',
          'pytz>=2018.3',
          'redis>=5.0.0,<6',
          'regex>=2020.6.8',
          'requests>=2.32.4,<3.0.0',
          'sortedcontainers>=2.4.0',
          'typing-extensions>=3.7.0',
          'zstandard>=0.18.0,<1',
          'pyyaml>=3.12,<7.0.0',
          'pymilvus>=2.5.10,<3.0.0',
          # Dynamic dependencies must be specified in a separate list, otherwise
          # Dependabot won't be able to parse the main list. Any dynamic
          # dependencies will not receive updates from Dependabot.
      ] + pyarrow_dependency,
      python_requires=python_requires,
      # BEAM-8840: Do NOT use tests_require or setup_requires.
      extras_require={
          'docs': [
              'jinja2>=3.0,<3.2',
              'Sphinx>=7.0.0,<8.0',
              'docstring-parser>=0.15,<1.0',
              'docutils>=0.18.1',
              'markdown',
              'pandas<2.3.0',
              'openai',
              'virtualenv-clone>=0.5,<1.0',
          ],
          'test': [
              'cloud-sql-python-connector[pg8000]>=1.0.0,<2.0.0',
              'docstring-parser>=0.15,<1.0',
              'freezegun>=0.3.12',
              'jinja2>=3.0,<3.2',
              'joblib>=1.0.1',
              'mock>=1.0.1,<6.0.0',
              'pandas<2.3.0',
              'parameterized>=0.7.1,<0.10.0',
              'pyhamcrest>=1.9,!=1.10.0,<3.0.0',
              'requests_mock>=1.7,<2.0',
              'tenacity>=8.0.0,<9',
              'pytest>=7.1.2,<8.0',
              'pytest-xdist>=2.5.0,<4',
              'pytest-timeout>=2.1.0,<3',
              'scikit-learn>=0.20.0',
              'sqlalchemy>=1.3,<3.0',
              'psycopg2-binary>=2.8.5,<2.9.10; python_version <= "3.9"',
              'psycopg2-binary>=2.8.5,<3.0; python_version >= "3.10"',
              'testcontainers[mysql,kafka,milvus]>=4.0.0,<5.0.0',
              'cryptography>=41.0.2',
              'hypothesis>5.0.0,<7.0.0',
              'virtualenv-clone>=0.5,<1.0',
              'mysql-connector-python>=9.3.0',
              'python-tds>=1.16.1',
              'sqlalchemy-pytds>=1.0.2',
              'oracledb>=3.1.1'
          ],
          'gcp': [
              'cachetools>=3.1.0,<7',
              'google-api-core>=2.0.0,<3',
              'google-apitools>=0.5.31,<0.5.32; python_version <= "3.12"',
              'google-apitools>=0.5.32,<0.5.33; python_version >= "3.13"',
              # NOTE: Maintainers, please do not require google-auth>=2.x.x
              # Until this issue is closed
              # https://github.com/googleapis/google-cloud-python/issues/10566
              'google-auth>=1.18.0,<3',
              'google-auth-httplib2>=0.1.0,<0.3.0',
              'google-cloud-datastore>=2.0.0,<3',
              'google-cloud-pubsub>=2.1.0,<3',
              'google-cloud-pubsublite>=1.2.0,<2',
              'google-cloud-storage>=2.18.2,<3',
              # GCP packages required by tests
              'google-cloud-bigquery>=2.0.0,<4',
              'google-cloud-bigquery-storage>=2.6.3,<3',
              'google-cloud-core>=2.0.0,<3',
              'google-cloud-bigtable>=2.19.0,<3',
              'google-cloud-spanner>=3.0.0,<4',
              # GCP Packages required by ML functionality
              'google-cloud-dlp>=3.0.0,<4',
              'google-cloud-language>=2.0,<3',
              'google-cloud-videointelligence>=2.0,<3',
              'google-cloud-vision>=2,<4',
              'google-cloud-recommendations-ai>=0.1.0,<0.11.0',
              'google-cloud-aiplatform>=1.26.0, < 2.0',
              # Authentication for Google Artifact Registry when using
              # --extra-index-url or --index-url in requirements.txt in
              # Dataflow, which allows installing python packages from private
              # Python repositories in GAR.
              'keyrings.google-artifactregistry-auth'
          ],
          'interactive': [
              'facets-overview>=1.1.0,<2',
              'google-cloud-dataproc>=5.0.0,<6',
              'ipython>=7,<9',
              'ipykernel>=6,<7',
              'ipywidgets>=8,<9',
              # Skip version 6.1.13 due to
              # https://github.com/jupyter/jupyter_client/issues/637
              'jupyter-client>=6.1.11,!=6.1.13,<8.2.1',
              'timeloop>=1.0.2,<2',
              'nbformat>=5.0.5,<6',
              'nbconvert>=6.2.0,<8',
          ] + dataframe_dependency,
          'interactive_test': [
              # headless chrome based integration tests
              'needle>=0.5.0,<1',
              'chromedriver-binary>=117,<118',
              # use a fixed major version of PIL for different python versions
              'pillow>=7.1.1,<10',
              # urllib 2.x is a breaking change for the headless chrome tests
              'urllib3<2,>=1.21.1'
          ],
          # Optional dependencies to unit-test ML functionality.
          # We don't expect users to install this extra. Users should install
          # necessary dependencies individually, or we should create targeted
          # extras. Keeping the bounds open as much as possible so that we
          # can find out early when Beam doesn't work with new versions.
          'ml_test': [
              'datatable',
              'embeddings',
              'langchain',
              'onnxruntime',
              'sentence-transformers',
              'skl2onnx',
              'pillow',
              'pyod',
              'tensorflow',
              'tensorflow-hub',
              'tensorflow-transform',
              'tf2onnx',
              'torch',
              'transformers',
              # Comment out xgboost as it is breaking presubmit python ml
              # tests due to tag check introduced since pip 24.2
              # https://github.com/apache/beam/issues/31285
              # 'xgboost<2.0',  # https://github.com/apache/beam/issues/31252
          ],
          'p312_ml_test': [
              'datatable',
              'embeddings',
              'onnxruntime',
              'langchain',
              'sentence-transformers',
              'skl2onnx',
              'pillow',
              'pyod',
              'tensorflow',
              'tensorflow-hub',
              'tf2onnx',
              'torch',
              'transformers',
          ],
          'aws': ['boto3>=1.9,<2'],
          'azure': [
              'azure-storage-blob>=12.3.2,<13',
              'azure-core>=1.7.0,<2',
              'azure-identity>=1.12.0,<2',
          ],
          'dataframe': dataframe_dependency,
          'dask': [
              'distributed >= 2024.4.2',
              'dask >= 2024.4.2',
              # For development, 'distributed >= 2023.12.1' should work with
              # the above dask PR, however it can't be installed as part of
              # a single `pip` call, since distributed releases are pinned to
              # specific dask releases. As a workaround, distributed can be
              # installed first, and then `.[dask]` installed second, with the
              # `--update` / `-U` flag to replace the dask release brought in
              # by distributed.
          ],
          'yaml': [
              'docstring-parser>=0.15,<1.0',
              'jinja2>=3.0,<3.2',
              'virtualenv-clone>=0.5,<1.0',
              # https://github.com/PiotrDabkowski/Js2Py/issues/317
              'js2py>=0.74,<1; python_version<"3.12"',
          ] + dataframe_dependency,
          # Keep the following dependencies in line with what we test against
          # in https://github.com/apache/beam/blob/master/sdks/python/tox.ini
          # For more info, see
          # https://docs.google.com/document/d/1c84Gc-cZRCfrU8f7kWGsNR2o8oSRjCM-dGHO9KvPWPw/edit?usp=sharing
          'torch': ['torch>=1.9.0,<2.8.0'],
          'tensorflow': ['tensorflow>=2.12rc1,<2.17'],
          'transformers': [
              'transformers>=4.28.0,<4.49.0',
              'tensorflow>=2.12.0',
              'torch>=1.9.0'
          ],
          'tft': ['tensorflow_transform>=1.14.0,<1.15.0'],
          'onnx': [
              'onnxruntime==1.13.1',
              'torch==1.13.1',
              'tensorflow==2.11.0',
              'tf2onnx==1.13.0',
              'skl2onnx==1.13',
              'transformers==4.25.1'
          ],
          'xgboost': ['xgboost>=1.6.0,<2.1.3', 'datatable==1.0.0'],
          'tensorflow-hub': ['tensorflow-hub>=0.14.0,<0.16.0']
      },
      zip_safe=False,
      # PyPI package information.
      classifiers=[
          'Intended Audience :: End Users/Desktop',
          'License :: OSI Approved :: Apache Software License',
          'Operating System :: POSIX :: Linux',
          'Programming Language :: Python :: 3.9',
          'Programming Language :: Python :: 3.10',
          'Programming Language :: Python :: 3.11',
          'Programming Language :: Python :: 3.12',
          'Programming Language :: Python :: 3.13',
          # When updating version classifiers, also update version warnings
          # above and in apache_beam/__init__.py.
          'Topic :: Software Development :: Libraries',
          'Topic :: Software Development :: Libraries :: Python Modules',
      ],
      license='Apache License, Version 2.0',
      keywords=PACKAGE_KEYWORDS,
      cmdclass={
          'mypy': mypy,
      },
  )
