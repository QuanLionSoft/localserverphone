param(
    [Parameter(Mandatory = $true)]
    [string]$RealAapt2,
    [string]$CscPath = (Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe')
)
$ErrorActionPreference = 'Stop'
$taskProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$taskOutputRoot = Join-Path $taskProjectRoot 'work\windows-aapt2-wrapper'
$taskSource = Join-Path $PSScriptRoot 'Aapt2DirectoryWrapper.cs'
$taskRealBinary = (Get-Item -LiteralPath $RealAapt2).FullName
$taskCompiler = (Get-Item -LiteralPath $CscPath).FullName
if ([IO.Path]::GetFileName($taskRealBinary) -ne 'aapt2.exe') {
    throw 'RealAapt2 must name the original official aapt2.exe executable.'
}
if (-not $taskOutputRoot.StartsWith($taskProjectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Wrapper output must remain within the project.'
}
New-Item -ItemType Directory -Path $taskOutputRoot -Force | Out-Null
$taskWrapper = Join-Path $taskOutputRoot 'aapt2.exe'
if ($taskRealBinary.Equals($taskWrapper, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The real AAPT2 cannot be the wrapper itself.'
}
& $taskRealBinary version
if ($LASTEXITCODE -ne 0) { throw 'The official AAPT2 version check failed.' }
& $taskCompiler /nologo /target:exe /optimize+ "/out:$taskWrapper" $taskSource
if ($LASTEXITCODE -ne 0) { throw 'The host wrapper could not be compiled.' }
[IO.File]::WriteAllText((Join-Path $taskOutputRoot 'real-aapt2-path.txt'), $taskRealBinary, [Text.UTF8Encoding]::new($false))
Write-Output "Wrapper generated: $taskWrapper"
Write-Output "Build: .\gradlew.bat :app:assembleDebug -Pandroid.aapt2FromMavenOverride=$taskWrapper"
