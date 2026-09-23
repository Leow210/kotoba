"""Build with the installed Android SDK; no Gradle/network dependencies."""
import os
import shutil
import subprocess
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'Library/Android/sdk')
TOOLS = SDK / 'build-tools/35.0.0'
JAVA = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path('/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin')
BUILD = ROOT / 'build'


def run(*args):
    subprocess.run([str(a) for a in args], check=True)


ANDROID='{http://schemas.android.com/apk/res/android}'
# Model files read straight from the APK: stored uncompressed.
NO_COMPRESS=['onnx','tflite']


def unpack_aars():
    """Android libraries (android/libs/aar, from tools/fetch_android_libs.py): each .aar gives classes, arm64 native
    libraries, assets, resources and manifest entries. Returns (jars, res zips, packages, manifests)."""
    source=ROOT/'libs'/'aar'
    if not source.is_dir():return [],[],[],[]
    out=BUILD/'aar';shutil.rmtree(out,ignore_errors=True);out.mkdir(parents=True)
    jars=[str(p) for p in sorted(source.glob('*.jar'))]
    zips,packages,manifests=[],[],[]
    for aar in sorted(source.glob('*.aar')):
        d=out/aar.stem
        with zipfile.ZipFile(aar) as z:z.extractall(d)
        if (d/'classes.jar').is_file():jars.append(str(d/'classes.jar'))
        jars+=[str(p) for p in sorted((d/'libs').glob('*.jar'))]
        if (d/'assets').is_dir():shutil.copytree(d/'assets',BUILD/'assets',dirs_exist_ok=True)
        if (d/'res').is_dir() and any((d/'res').iterdir()):
            flat=out/(aar.stem+'.res.zip')
            run(TOOLS/'aapt2','compile','--dir',d/'res','-o',flat)
            zips.append(flat)
        m=d/'AndroidManifest.xml'
        if m.is_file():
            pkg=ET.parse(m).getroot().get('package')
            if pkg and pkg not in packages and pkg!='app.kotoba.reader':packages.append(pkg)
            manifests.append(m)
    return jars,zips,packages,manifests


def merged_manifest(manifests):
    """The app's manifest plus the libraries' components (providers, services, receivers, activities, meta-data).
    Their permissions are left out: Kotoba has no internet access."""
    ET.register_namespace('android',ANDROID[1:-1])
    tree=ET.parse(ROOT/'AndroidManifest.xml');app=tree.getroot().find('application')
    known={(c.tag,c.get(ANDROID+'name')):c for c in app}
    for m in manifests:
        lib=ET.parse(m).getroot().find('application')
        if lib is None:continue
        for c in list(lib):
            for el in c.iter():
                for k in list(el.attrib):
                    if k.startswith('{http://schemas.android.com/tools}'):del el.attrib[k]
                    elif '${applicationId}' in el.attrib[k]:el.attrib[k]=el.attrib[k].replace('${applicationId}','app.kotoba.reader')
            key=(c.tag,c.get(ANDROID+'name'))
            if key in known:
                # Same component from several libraries (androidx.startup's InitializationProvider): merge its meta-data.
                have={x.get(ANDROID+'name') for x in known[key]}
                for x in c:
                    if x.get(ANDROID+'name') not in have:known[key].append(x)
                continue
            app.append(c);known[key]=c
    path=BUILD/'AndroidManifest.xml';tree.write(path,encoding='utf-8',xml_declaration=True)
    return path


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
    shutil.rmtree(BUILD/'generated',ignore_errors=True);(BUILD/'generated').mkdir()
    aar_jars,aar_res,aar_packages,aar_manifests=unpack_aars()
    manifest=merged_manifest(aar_manifests) if aar_manifests else ROOT/'AndroidManifest.xml'
    run(TOOLS/'aapt2','compile','--dir',ROOT/'res','-o',BUILD/'resources.zip')
    link=[TOOLS/'aapt2','link','-I',platform,'--manifest',manifest,'--java',BUILD/'generated','-A',BUILD/'assets']
    for ext in NO_COMPRESS:link+=['-0',ext]
    if aar_res:
        link+=['--auto-add-overlay','--extra-packages',':'.join(aar_packages)]
        for z in aar_res:link+=['-R',z]
    run(*link,BUILD/'resources.zip','-o',BUILD/'base.apk')
    sources=list((ROOT/'src').rglob('*.java'))+list((BUILD/'generated').rglob('*.java'))
    # Code that needs those libraries (the PaddleOCR-VL reader) is only compiled when they're there.
    if aar_jars:sources+=list((ROOT/'src-extra').rglob('*.java'))
    # Third-party: ONNX Runtime (on-device OCR) and LiteRT-LM. Java classes are dexed with the app; native libraries go in lib/.
    jars=[str(p) for p in (ROOT/'libs').rglob('*.jar') if 'aar' not in p.parts]+aar_jars
    classpath=os.pathsep.join([str(platform)]+jars)
    run(JAVA/'javac','-encoding','UTF-8','--release','11','-classpath',classpath,'-d',BUILD/'classes',*sources)
    run(JAVA/'java','-cp',TOOLS/'lib/d8.jar','com.android.tools.r8.D8','--lib',platform,'--min-api','26','--output',BUILD/'dex',*list((BUILD/'classes').rglob('*.class')),*jars)
    shutil.copy2(BUILD/'base.apk',BUILD/'unsigned.apk')
    with zipfile.ZipFile(BUILD/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as apk:
        for dex in (BUILD/'dex').glob('*.dex'):
            apk.write(dex,dex.name)
        for so in (ROOT/'libs').rglob('jni/*/*.so'):
            apk.write(so,'lib/'+so.parent.name+'/'+so.name)
        # The libraries' native code, 64-bit ARM only (like ONNX Runtime).
        for so in (BUILD/'aar').glob('*/jni/arm64-v8a/*.so'):
            apk.write(so,'lib/arm64-v8a/'+so.name)
    run(TOOLS/'zipalign','-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk')
    run(TOOLS/'apksigner','sign','--ks',Path.home()/'.android/debug.keystore','--ks-pass','pass:android','--key-pass','pass:android','--out',BUILD/'kotoba.apk',BUILD/'aligned.apk')
    run(TOOLS/'apksigner','verify',BUILD/'kotoba.apk')
    print(BUILD/'kotoba.apk')


if __name__=='__main__':
    build()
