"""Build with the installed Android SDK; no Gradle/network dependencies."""
import os
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'Library/Android/sdk')
TOOLS = SDK / 'build-tools/35.0.0'
JAVA = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path('/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin')
BUILD = ROOT / 'build'


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


def build():
    for name in ['classes','generated','assets','dex']:
        (BUILD/name).mkdir(parents=True,exist_ok=True)
    shutil.rmtree(BUILD/'assets',ignore_errors=True)
    shutil.rmtree(BUILD/'classes',ignore_errors=True)
    shutil.rmtree(BUILD/'dex',ignore_errors=True)
    for name in ['classes','assets','dex']:
        (BUILD/name).mkdir(parents=True,exist_ok=True)
    for source in (ROOT/'assets').iterdir():
        if source.is_file():
            shutil.copy2(source,BUILD/'assets'/source.name)
        elif source.is_dir():
            shutil.copytree(source,BUILD/'assets'/source.name)
    platform=SDK/'platforms/android-35/android.jar'
    run(TOOLS/'aapt2','compile','--dir',ROOT/'res','-o',BUILD/'resources.zip')
    run(TOOLS/'aapt2','link','-I',platform,'--manifest',ROOT/'AndroidManifest.xml','-0','onnx','--java',BUILD/'generated','-A',BUILD/'assets',BUILD/'resources.zip','-o',BUILD/'base.apk')
    sources=list((ROOT/'src').rglob('*.java'))+list((BUILD/'generated').rglob('*.java'))
    # Third-party: ONNX Runtime (on-device OCR). Java classes are dexed with the app; native libraries go in lib/.
    jars=[str(p) for p in (ROOT/'libs').rglob('*.jar')]
    classpath=os.pathsep.join([str(platform)]+jars)
    run(JAVA/'javac','-encoding','UTF-8','--release','11','-classpath',classpath,'-d',BUILD/'classes',*sources)
    run(JAVA/'java','-cp',TOOLS/'lib/d8.jar','com.android.tools.r8.D8','--lib',platform,'--min-api','26','--output',BUILD/'dex',*list((BUILD/'classes').rglob('*.class')),*jars)
    shutil.copy2(BUILD/'base.apk',BUILD/'unsigned.apk')
    with zipfile.ZipFile(BUILD/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as apk:
        for dex in (BUILD/'dex').glob('*.dex'):
            apk.write(dex,dex.name)
        for so in (ROOT/'libs').rglob('jni/*/*.so'):
            apk.write(so,'lib/'+so.parent.name+'/'+so.name)
    run(TOOLS/'zipalign','-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk')
    run(TOOLS/'apksigner','sign','--ks',Path.home()/'.android/debug.keystore','--ks-pass','pass:android','--key-pass','pass:android','--out',BUILD/'kotoba.apk',BUILD/'aligned.apk')
    run(TOOLS/'apksigner','verify',BUILD/'kotoba.apk')
    print(BUILD/'kotoba.apk')


if __name__=='__main__':
    build()
