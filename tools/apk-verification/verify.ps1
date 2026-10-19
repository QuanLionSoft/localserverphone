param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [Parameter(Mandatory = $true)]
    [string]$BuildToolsDirectory,
    [string]$ProjectRoot = (Join-Path $PSScriptRoot '..\..'),
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$PythonExecutable = 'python',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\work\apk-verification')
)
$ErrorActionPreference = 'Stop'
$taskApk = (Get-Item -LiteralPath $ApkPath).FullName
$taskProject = (Get-Item -LiteralPath $ProjectRoot).FullName
$taskTools = (Get-Item -LiteralPath $BuildToolsDirectory).FullName
$taskResults = [IO.Path]::GetFullPath($OutputDirectory)
foreach ($taskToolName in @('apksigner.bat', 'zipalign.exe', 'aapt2.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $taskTools $taskToolName) -PathType Leaf)) {
        throw "Official SDK Build Tools file missing: $taskToolName"
    }
}
if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
    $taskJava = Join-Path $JavaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $taskJava -PathType Leaf)) {
        throw 'JavaHome must contain bin\java.exe.'
    }
    $env:JAVA_HOME = (Get-Item -LiteralPath $JavaHome).FullName
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}
[void](Get-Command $PythonExecutable -ErrorAction Stop)
[void][IO.Directory]::CreateDirectory($taskResults)
& (Join-Path $taskTools 'apksigner.bat') verify --verbose --print-certs $taskApk *> (Join-Path $taskResults 'apksigner.log')
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed; see apksigner.log.' }
& (Join-Path $taskTools 'zipalign.exe') -c -P 16 -v 4 $taskApk *> (Join-Path $taskResults 'zipalign.log')
if ($LASTEXITCODE -ne 0) { throw 'APK alignment verification failed; see zipalign.log.' }
& (Join-Path $taskTools 'aapt2.exe') dump badging $taskApk *> (Join-Path $taskResults 'badging.log')
if ($LASTEXITCODE -ne 0) { throw 'Official AAPT2 could not read APK metadata; see badging.log.' }
& $PythonExecutable (Join-Path $PSScriptRoot 'verify_apk.py') $taskApk $taskProject *> (Join-Path $taskResults 'contents.json')
if ($LASTEXITCODE -ne 0) { throw 'APK contents verification failed; see contents.json.' }
Get-Content (Join-Path $taskResults 'apksigner.log') -TotalCount 12
Get-Content (Join-Path $taskResults 'zipalign.log') -Tail 1
Get-Content (Join-Path $taskResults 'contents.json')
