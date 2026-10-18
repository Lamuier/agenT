<#
.SYNOPSIS
    agenT Windows 构建入口；macOS / Linux 使用 build.sh，标准流程见 build.md。

.DESCRIPTION
    .\build.ps1                 # Debug 构建
    .\build.ps1 -Install        # Debug 构建 + adb 安装
    .\build.ps1 -Release        # Release 时间戳包到 dist/
    .\build.ps1 -Release -OfficialRelease  # 正式包，App名称-版本号
    .\build.ps1 -SetupSigning   # 配置/重绑 Release 签名

.PARAMETER Clean
    构建前执行 clean。Release 默认不 clean（Windows 上易被 Studio/Gradle 锁文件）。
    显式 -Clean 时会先 gradlew --stop 并重试删除。

.PARAMETER Offline
    Gradle 离线模式（Release 默认开启；Debug 默认联网）。

.PARAMETER Online
    Release 时允许联网拉依赖。

.PARAMETER SkipChecks
    跳过 unit test / lint。

.PARAMETER AdoptDebugKeystore / AdoptKeystore / GenerateNewKey / ForceRebind
    旧选项已停用；-SetupSigning 只配置统一 release.keystore 与共享私有密码文件。
#>
param(
    [switch]$Install,
    [switch]$Release,
    [switch]$OfficialRelease,
    [string]$ArtifactSuffix = "",
    [switch]$SetupSigning,
    [switch]$Clean,
    [switch]$Offline,
    [switch]$Online,
    [switch]$SkipChecks,
    [switch]$GenerateBaselineProfile,
    [switch]$PerformanceReview,
    [string]$SigningRoot = (Join-Path $PSScriptRoot ".signing"),
    [switch]$AdoptDebugKeystore,
    [string]$AdoptKeystore,
    [string]$KeyAlias,
    [string]$StorePassword,
    [switch]$GenerateNewKey,
    [switch]$ForceRebind
)

$ErrorActionPreference = "Stop"
$ProjectRoot = $PSScriptRoot
$JavaHome = "C:\Program Files\Android\Android Studio\jbr"

Set-Location -LiteralPath $ProjectRoot

# --- helpers ---

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Resolve-JavaHome {
    $java = Join-Path $JavaHome "bin\java.exe"
    if (-not (Test-Path -LiteralPath $java)) {
        throw "找不到 Android Studio JBR: $JavaHome（请安装 Android Studio 或调整 build.ps1 中的路径）"
    }
    return $JavaHome
}

function Resolve-AndroidSdk {
    $candidates = @()
    $localProps = Join-Path $ProjectRoot "local.properties"
    if (Test-Path -LiteralPath $localProps) {
        $line = Get-Content -LiteralPath $localProps |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' } |
            Select-Object -First 1
        if ($line -match 'sdk\.dir\s*=\s*(.+)$') {
            $backslash = [string][char]0x5C
            $candidates += (($Matches[1].Trim() -replace '\\:', ':') -replace '\\\\', $backslash)
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) { $candidates += $env:ANDROID_HOME }
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) { $candidates += $env:ANDROID_SDK_ROOT }
    $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk")

    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) { continue }
        # Android 17 起平台目录带次级版本号（android-37.0），兼容旧式 android-37 目录。
        if ((Test-Path -LiteralPath "$candidate\platforms\android-37.0\android.jar") -or
            (Test-Path -LiteralPath "$candidate\platforms\android-37\android.jar")) {
            return $candidate
        }
    }
    throw "找不到 Android SDK 37。已检查: $($candidates -join '; ')"
}

function Get-AppVersion {
    $gradleConfig = Get-Content -Raw -LiteralPath (Join-Path $ProjectRoot "app\build.gradle.kts")
    $versionCodeMatch = [regex]::Match($gradleConfig, 'versionCode\s*=\s*(\d+)')
    $versionNameMatch = [regex]::Match($gradleConfig, 'versionName\s*=\s*"([^"]+)"')
    if (-not $versionCodeMatch.Success -or -not $versionNameMatch.Success) {
        throw "无法从 app\build.gradle.kts 读取 versionCode / versionName"
    }
    [xml]$strings = Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $ProjectRoot "app\src\main\res\values\strings.xml")
    $names = @($strings.SelectNodes("/resources/string[@name='app_name']"))
    if ($names.Count -ne 1) { throw "默认 strings.xml 必须提供唯一的 app_name" }
    return [pscustomobject]@{
        AppName = $names[0].InnerText.Trim()
        VersionCode = [int]$versionCodeMatch.Groups[1].Value
        VersionName = $versionNameMatch.Groups[1].Value
    }
}

function Invoke-Native([string]$Executable, [string[]]$Arguments) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        return @(& $Executable @Arguments 2>&1)
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Invoke-Gradle([string[]]$GradleArgs) {
    $env:JAVA_HOME = Resolve-JavaHome
    $env:ANDROID_HOME = Resolve-AndroidSdk
    # 必须写到 Host：否则 Gradle 日志会进入函数返回值，污染 $apk = Invoke-Assemble ...
    # JDK 会向 stderr 输出 restricted-method 警告；全局 ErrorActionPreference=Stop 时
    # 这些警告会被当成终止性错误中断构建，故此处临时降为 Continue，成败仍看 $LASTEXITCODE。
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & (Join-Path $ProjectRoot "gradlew.bat") @GradleArgs 2>&1 | ForEach-Object {
            Write-Host $_
        }
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle 失败 (exit $LASTEXITCODE): $($GradleArgs -join ' ')"
    }
}

function Stop-GradleDaemons {
    Write-Step "停止 Gradle daemon（释放 app\build 文件锁）"
    $env:JAVA_HOME = Resolve-JavaHome
    $env:ANDROID_HOME = Resolve-AndroidSdk
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & (Join-Path $ProjectRoot "gradlew.bat") "--stop" | Out-Host
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Clear-LintCache {
    $lintCache = Join-Path $ProjectRoot "app\build\intermediates\lint-cache"
    if (-not (Test-Path -LiteralPath $lintCache)) { return }
    try {
        Remove-Item -LiteralPath $lintCache -Recurse -Force -ErrorAction Stop
        Write-Host "  已清理 lint-cache"
    } catch {
        Write-Host "  lint-cache 仍被占用（可关闭 Android Studio 后重试）" -ForegroundColor Yellow
    }
}

function Clear-AppBuildDirectory {
    $buildDir = Join-Path $ProjectRoot "app\build"
    if (-not (Test-Path -LiteralPath $buildDir)) { return }

    Stop-GradleDaemons

    $attempts = 3
    for ($i = 1; $i -le $attempts; $i++) {
        try {
            Remove-Item -LiteralPath $buildDir -Recurse -Force -ErrorAction Stop
            Write-Host "  已删除 app\build"
            return
        } catch {
            if ($i -eq $attempts) {
                throw @"
无法删除 app\build（文件被占用）。

请关闭 Android Studio 对本工程的打开/索引，或手动删除：
  $buildDir

然后重试。也可不加 -Clean 做增量构建。
"@
            }
            Write-Host "  删除失败，第 $i/$attempts 次重试…" -ForegroundColor Yellow
            Start-Sleep -Seconds 2
            Stop-GradleDaemons
        }
    }
}

function Get-LatestBuildTools([string]$Sdk) {
    $dir = Get-ChildItem -LiteralPath (Join-Path $Sdk "build-tools") -Directory |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1
    if ($null -eq $dir) { throw "Android build-tools 未找到: $Sdk" }
    return $dir.FullName
}

# --- signing ---

function Get-CertSha256([string]$CertPath) {
    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($CertPath)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($certificate.RawData))).Replace("-", "").ToUpperInvariant()
    } finally {
        $sha.Dispose()
        $certificate.Dispose()
    }
}

function Invoke-SetupSigning {
    if ($AdoptDebugKeystore -or $GenerateNewKey -or $ForceRebind -or -not [string]::IsNullOrEmpty($StorePassword)) {
        throw "已统一使用 release.keystore；不支持 Debug 密钥、新建密钥、重绑或命令行密码。请使用共享私有密码文件。"
    }
    $store = (Resolve-Path -LiteralPath (Join-Path $ProjectRoot "../release.keystore")).Path
    if ($AdoptKeystore -and (Resolve-Path -LiteralPath $AdoptKeystore).Path -ne $store) {
        throw "只允许引用工程同级的 release.keystore"
    }
    if ($KeyAlias -and $KeyAlias -ne "release") { throw "统一密钥别名必须为 release" }
    $passwordFile = Join-Path $ProjectRoot "../release-password.txt"
    $certificateFile = Join-Path $ProjectRoot "../release-cert.cer"
    $expected = "5342F38CB24B950ED3B7112EC82C151B98A7ACBE67A1FA2EA3D0AA9F79C36FF3"
    if (-not (Test-Path -LiteralPath $passwordFile) -or -not (Test-Path -LiteralPath $certificateFile)) {
        throw "共享密码文件或统一公钥证书缺失；见 build.md"
    }
    if ((Get-CertSha256 $certificateFile) -ne $expected) { throw "共享公钥证书与统一指纹不一致" }
    $metadataFile = Join-Path $SigningRoot "release-signing.json"
    if (Test-Path -LiteralPath $metadataFile) {
        $existing = Get-Content -Raw -LiteralPath $metadataFile | ConvertFrom-Json
        if (([string]$existing.certificateSha256).Replace(":", "").ToUpperInvariant() -ne $expected) {
            throw "已有其他签名身份；拒绝自动重绑"
        }
    }
    $keytool = Join-Path (Resolve-JavaHome) "bin/keytool.exe"
    $temporaryCertificate = [IO.Path]::GetTempFileName()
    $env:AGENTT_KEYTOOL_PASS = [IO.File]::ReadAllText($passwordFile, [Text.Encoding]::UTF8).TrimEnd("`r", "`n")
    try {
        if ([string]::IsNullOrEmpty($env:AGENTT_KEYTOOL_PASS) -or $env:AGENTT_KEYTOOL_PASS -match "[\r\n]") {
            throw "密码文件须为 UTF-8 单行"
        }
        $null = Invoke-Native $keytool @("-exportcert", "-keystore", $store, "-alias", "release",
            "-storepass:env", "AGENTT_KEYTOOL_PASS", "-file", $temporaryCertificate)
        if ($LASTEXITCODE -ne 0) { throw "原密钥库密码或证书读取失败" }
        if ((Get-CertSha256 $temporaryCertificate) -ne $expected) { throw "密钥库证书与统一指纹不一致" }
        $null = Invoke-Native $keytool @("-certreq", "-keystore", $store, "-alias", "release",
            "-storepass:env", "AGENTT_KEYTOOL_PASS", "-keypass:env", "AGENTT_KEYTOOL_PASS")
        if ($LASTEXITCODE -ne 0) { throw "原私钥解密与签名校验失败" }
        New-Item -ItemType Directory -Force -Path $SigningRoot | Out-Null
        [ordered]@{
            schemaVersion = 2
            storeFile = "../release.keystore"
            credentialFile = "../release-password.txt"
            credentialFormat = "text"
            keyAlias = "release"
            certificateFile = "../release-cert.cer"
            certificateSha256 = $expected
            keyPasswordSameAsStore = $true
            source = "shared-release-keystore"
        } | ConvertTo-Json | Set-Content -LiteralPath $metadataFile -Encoding utf8
        Set-Content -LiteralPath (Join-Path $SigningRoot "pinned-certificate.sha256") -Value $expected -Encoding ascii
        Write-Host "已配置统一 release.keystore；未复制私钥或密码。"
    } finally {
        $env:AGENTT_KEYTOOL_PASS = $null
        Remove-Item -LiteralPath $temporaryCertificate -Force -ErrorAction SilentlyContinue
    }
}

function Import-ReleaseSigningEnv([string]$MetadataFile) {
    $metadata = Get-Content -Raw -LiteralPath $MetadataFile | ConvertFrom-Json
    foreach ($requiredPath in @($metadata.storeFile, $metadata.credentialFile, $metadata.certificateFile)) {
        if (-not (Test-Path -LiteralPath $requiredPath)) {
            throw "签名资产缺失: $requiredPath"
        }
    }
    if ($metadata.credentialFormat -eq "text") {
        $plainPassword = (Get-Content -Raw -LiteralPath $metadata.credentialFile).TrimEnd("`r", "`n")
        if ([string]::IsNullOrEmpty($plainPassword)) { throw "签名密码文件为空" }
    } else {
        $credential = Import-Clixml -LiteralPath $metadata.credentialFile
        $plainPassword = $credential.GetNetworkCredential().Password
    }
    $env:AGENTT_RELEASE_STORE_FILE = (Resolve-Path -LiteralPath $metadata.storeFile).Path
    $env:AGENTT_RELEASE_STORE_PASSWORD = $plainPassword
    $env:AGENTT_RELEASE_KEY_ALIAS = [string]$metadata.keyAlias
    $env:AGENTT_RELEASE_KEY_PASSWORD = $plainPassword
    return [pscustomobject]@{
        Metadata = $metadata
        PlainPassword = $plainPassword
    }
}

function Clear-ReleaseSigningEnv {
    foreach ($name in @(
        "AGENTT_RELEASE_STORE_FILE",
        "AGENTT_RELEASE_STORE_PASSWORD",
        "AGENTT_RELEASE_KEY_ALIAS",
        "AGENTT_RELEASE_KEY_PASSWORD"
    )) {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
}

function Ensure-ReleaseSigningConfigured {
    $metadataFile = Join-Path $SigningRoot "release-signing.json"
    if (Test-Path -LiteralPath $metadataFile) { return $metadataFile }
    throw "缺少统一签名配置；请先准备 release.keystore 和共享私有密码，再运行 .\build.ps1 -SetupSigning（见 build.md）。禁止自动绑定 Debug 密钥。"
}

# --- verify ---

function Test-ReleaseApk {
    param(
        [string]$Apk,
        [string]$MetadataFile,
        [int]$ExpectedVersionCode,
        [string]$ExpectedVersionName,
        [string]$Sdk
    )

    $env:JAVA_HOME = Resolve-JavaHome
    $env:ANDROID_HOME = $Sdk
    $apkPath = (Resolve-Path -LiteralPath $Apk).Path

    $buildTools = Get-LatestBuildTools $Sdk
    $apkSigner = Join-Path $buildTools "apksigner.bat"
    $zipAlign = Join-Path $buildTools "zipalign.exe"
    $aapt = Join-Path $buildTools "aapt.exe"
    $metadata = Get-Content -Raw -LiteralPath $MetadataFile | ConvertFrom-Json

    $signatureOutput = Invoke-Native $apkSigner @("verify", "--verbose", "--print-certs", $apkPath)
    $signatureOutput | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { throw "APK 签名校验失败" }

    $digestLine = $signatureOutput | Where-Object { $_ -match "certificate SHA-256 digest:\s*([0-9a-fA-F]+)" } | Select-Object -First 1
    if ($null -eq $digestLine) { throw "无法读取 APK 证书指纹" }
    $actualFingerprint = ([regex]::Match($digestLine, "certificate SHA-256 digest:\s*([0-9a-fA-F]+)").Groups[1].Value).ToUpperInvariant()
    $expectedFingerprint = ([string]$metadata.certificateSha256).Replace(":", "").ToUpperInvariant()
    if ($actualFingerprint -ne $expectedFingerprint) {
        throw "签名证书不匹配。期望 $expectedFingerprint，实际 $actualFingerprint"
    }

    [void](Invoke-Native $zipAlign @("-c", "-P", "16", "-v", "4", $apkPath))
    if ($LASTEXITCODE -ne 0) { throw "zipalign 校验失败" }

    $badging = Invoke-Native $aapt @("dump", "badging", $apkPath)
    if ($LASTEXITCODE -ne 0) { throw "无法读取 APK badging" }
    $packageLine = $badging | Where-Object { $_ -like "package:*" } | Select-Object -First 1
    if ($packageLine -notmatch "name='com\.lamuier\.agenT'") { throw "包名不正确: $packageLine" }
    if ($packageLine -notmatch "versionCode='$ExpectedVersionCode'") { throw "versionCode 不正确: $packageLine" }
    $escapedVersionName = [regex]::Escape($ExpectedVersionName)
    if ($packageLine -notmatch "versionName='$escapedVersionName'") { throw "versionName 不正确: $packageLine" }
    if (($badging | Where-Object { $_ -like "sdkVersion:*" } | Select-Object -First 1) -notmatch "'26'") {
        throw "minSdkVersion 不正确"
    }
    if (($badging | Where-Object { $_ -like "targetSdkVersion:*" } | Select-Object -First 1) -notmatch "'37'") {
        throw "targetSdkVersion 不正确"
    }

    $permissions = Invoke-Native $aapt @("dump", "permissions", $apkPath)
    if ($LASTEXITCODE -ne 0) { throw "无法读取 APK 权限" }
    $permissionNames = foreach ($line in $permissions) {
        if ($line -match "uses-permission: name='([^']+)'") { $Matches[1] }
    }
    $unexpectedPermissions = $permissionNames | Where-Object {
        $_ -notin @(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.USE_BIOMETRIC",
            "android.permission.USE_FINGERPRINT",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.POST_PROMOTED_NOTIFICATIONS"
        ) -and -not $_.EndsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
    if ($unexpectedPermissions) { throw "意外权限: $($unexpectedPermissions -join ', ')" }
    foreach ($required in @("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")) {
        if ($required -notin $permissionNames) { throw "缺少权限: $required" }
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
    try {
        $profileRoot = Join-Path $ProjectRoot "app\src\release\generated\baselineProfiles"
        foreach ($profileName in @("baseline-prof.txt", "startup-prof.txt")) {
            $profilePath = Join-Path $profileRoot $profileName
            if (-not (Test-Path -LiteralPath $profilePath)) { throw "缺少应用编译配置，请先运行 -GenerateBaselineProfile" }
            $profileText = Get-Content -Raw -LiteralPath $profilePath
            if (-not $profileText.Contains("Lcom/lamuier/agenT/MainActivity;") -or $profileText.Contains("Lcom/lamuier/agenT/qa/")) {
                throw "应用编译配置缺少启动路径或混入夹具"
            }
        }
        foreach ($profileName in @("baseline.prof", "baseline.profm")) {
            $entry = $archive.GetEntry("assets/dexopt/$profileName")
            if ($null -eq $entry -or $entry.Length -le 8) { throw "Release 缺少有效编译配置资产" }
            $reader = [IO.BinaryReader]::new($entry.Open())
            try {
                $magic = [Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
                $expectedMagic = if ($profileName -eq "baseline.prof") { "pro`0" } else { "prm`0" }
                if ($magic -ne $expectedMagic) { throw "Release 编译配置格式错误" }
            } finally { $reader.Dispose() }
        }
        foreach ($entry in $archive.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' }) {
            $reader = [IO.BinaryReader]::new($entry.Open())
            try {
                $dexText = [Text.Encoding]::UTF8.GetString($reader.ReadBytes([int]$entry.Length))
                foreach ($marker in @("UiReviewActivity", "ReviewFixture", "PerformanceFixtureActivity", "org/robolectric/", "androidx/benchmark/", "androidx/test/runner/")) {
                    if ($dexText.Contains($marker)) { throw "Release 混入验收入口或测试运行库" }
                }
            } finally { $reader.Dispose() }
        }
        $nativeAbis = $archive.Entries |
            Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' } |
            ForEach-Object { $_.FullName.Split('/')[1] } |
            Sort-Object -Unique
    } finally {
        $archive.Dispose()
    }
    $unexpectedAbis = $nativeAbis | Where-Object { $_ -notin @('arm64-v8a') }
    if ($unexpectedAbis) { throw "不支持的 ABI: $($unexpectedAbis -join ', ')" }
    foreach ($required in @('arm64-v8a')) {
        if ($required -notin $nativeAbis) { throw "缺少 ABI: $required" }
    }

    $manifestLines = Invoke-Native $aapt @("dump", "xmltree", $apkPath, "AndroidManifest.xml")
    if ($LASTEXITCODE -ne 0) { throw "无法读取 AndroidManifest.xml" }
    $manifest = $manifestLines -join "`n"
    if ($manifest -match "UiReviewActivity|PreviewActivity|PerformanceFixtureActivity") { throw "Release Manifest 混入验收入口" }
    if ($manifest -match "android:debuggable") { throw "Release APK 可调试" }
    if ($manifest -match "android:networkSecurityConfig") {
        # 资源收缩后 APK 内仅剩数字资源 ID（无 Raw 字符串），
        # 故在源码层校验指向 @xml/network_security_config（ECH 域加密 + 禁明文）
        $sourceManifest = Get-Content -Raw -LiteralPath (Join-Path $ProjectRoot "app\src\main\AndroidManifest.xml")
        if ($sourceManifest -notmatch 'android:networkSecurityConfig\s*=\s*"@xml/network_security_config"') {
            throw "AndroidManifest.xml 的 networkSecurityConfig 未指向 @xml/network_security_config"
        }
        foreach ($nscSource in @(
            (Join-Path $ProjectRoot "app\src\main\res\xml\network_security_config.xml"),
            (Join-Path $ProjectRoot "app\src\main\res\xml-v37\network_security_config.xml")
        )) {
            if (-not (Test-Path -LiteralPath $nscSource)) { throw "缺少网络安全配置源: $nscSource" }
            $nscContent = Get-Content -Raw -LiteralPath $nscSource
            if ($nscContent -match 'cleartextTrafficPermitted\s*=\s*"true"') {
                throw "网络安全配置允许明文流量: $nscSource"
            }
        }
    }
    if ($manifest -notmatch "android:allowBackup[^\n]*0x0") { throw "allowBackup 不是 false" }
    if ($manifest -notmatch "android:usesCleartextTraffic[^\n]*0x0") { throw "usesCleartextTraffic 不是 false" }

    Write-Host "校验通过: com.lamuier.agenT $ExpectedVersionName ($ExpectedVersionCode)"
    Write-Host "  ABIs      : $($nativeAbis -join ', ')"
    Write-Host "  Cert SHA256: $actualFingerprint"
}

# --- build actions ---

function Invoke-Assemble {
    param(
        [ValidateSet("Debug", "Release")]
        [string]$Variant,
        [switch]$DoClean,
        [switch]$DoOffline,
        [switch]$DoSkipChecks
    )

    if ($DoClean) {
        Write-Step "Clean app\build"
        Clear-AppBuildDirectory
    } else {
        # 避免 Studio / 旧 daemon 锁住 lint-cache（Windows 常见）
        Stop-GradleDaemons
        Clear-LintCache
    }

    $arguments = @()
    # 单次构建用完即停，降低 Windows 文件锁概率
    $arguments += "--no-daemon"
    if ($DoOffline) { $arguments += "--offline" }
    if (-not $DoSkipChecks) {
        $arguments += "testDebugUnitTest"
        if ($Variant -eq "Release") {
            # Release 只跑 vital lint，避免与 lintDebug 争用同一批 cache jar
            $arguments += "lintVitalRelease"
        } else {
            $arguments += "lintDebug"
        }
    }
    $arguments += ":app:assemble$Variant"
    if ($GenerateBaselineProfile -or $PerformanceReview) {
        $perfPython = Get-Command python -ErrorAction SilentlyContinue
        if ($null -eq $perfPython) { $perfPython = Get-Command python3 -ErrorAction Stop }
        $perfSdk = Resolve-AndroidSdk
        & $perfPython.Source (Join-Path $ProjectRoot "scripts\performance_support.py") --sdk $perfSdk
        if ($LASTEXITCODE -ne 0) { throw "性能模拟器身份检查失败" }
        $env:ANDROID_SERIAL = $env:AGENT_PERFORMANCE_SERIAL
        if ($GenerateBaselineProfile) {
            $arguments = @($arguments | Where-Object { $_ -ne ":app:assemble$Variant" })
            $arguments += ":app:generateReleaseBaselineProfile"
            $arguments += "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile"
            $arguments += ":app:assemble$Variant"
        } else {
            $arguments += ":performance:connectedBenchmarkReleaseAndroidTest"
            $arguments += "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark"
        }
        $arguments += "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR"
    }

    Write-Step "Gradle assemble$Variant"
    $attempts = 2
    for ($i = 1; $i -le $attempts; $i++) {
        try {
            Invoke-Gradle $arguments
            if ($PerformanceReview) {
                & $perfPython.Source (Join-Path $ProjectRoot "scripts\performance_support.py") --sdk $perfSdk --collect
                if ($LASTEXITCODE -ne 0) { throw "未取得完整性能报告" }
            }
            break
        } catch {
            $msg = "$_"
            $isLock = $msg -match 'FileSystemException|正在使用此文件|cannot access|AccessDenied|Unable to delete'
            if (-not $isLock -or $i -eq $attempts) { throw }
            Write-Host "  检测到文件锁，停止 daemon、清理 lint-cache 后重试 ($i/$attempts)…" -ForegroundColor Yellow
            Stop-GradleDaemons
            Start-Sleep -Seconds 2
            Clear-LintCache
        }
    }

    $variantFolder = $Variant.ToLowerInvariant()
    $apk = Join-Path $ProjectRoot "app\build\outputs\apk\$variantFolder\app-$variantFolder.apk"
    if (-not (Test-Path -LiteralPath $apk)) {
        throw "未生成 APK: $apk"
    }
    Write-Host "APK: $apk"
    return $apk
}

function Invoke-DebugBuild {
    $useOffline = $Offline.IsPresent
    Invoke-Assemble -Variant Debug -DoClean:$Clean -DoOffline:$useOffline -DoSkipChecks:$SkipChecks | Out-Null
}

function Invoke-InstallDebug {
    $apk = Invoke-Assemble -Variant Debug -DoClean:$Clean -DoOffline:$Offline -DoSkipChecks:$SkipChecks
    $sdk = Resolve-AndroidSdk
    $adb = Join-Path $sdk "platform-tools\adb.exe"
    if (-not (Test-Path -LiteralPath $adb)) { throw "找不到 adb: $adb" }

    Write-Step "adb install"
    & $adb devices
    if ($LASTEXITCODE -ne 0) { throw "adb devices 失败" }
    & $adb install -r $apk
    if ($LASTEXITCODE -ne 0) { throw "adb install 失败" }
    Write-Host "已安装: $apk" -ForegroundColor Green
}

function Get-ReleaseArtifactBase([string]$AppName, [string]$VersionName, [DateTimeOffset]$StartedAt) {
    if ([string]::IsNullOrWhiteSpace($AppName) -or $AppName -ne $AppName.Trim() -or
        $AppName -match '[\x00-\x1f\x7f<>:"/\\|?*]' -or $AppName.EndsWith(".") -or $AppName.EndsWith(" ") -or
        $AppName -in @(".", "..")) { throw "App 名称不适合用作产物文件名" }
    if ($VersionName -notmatch '^\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?$') {
        throw "版本号不符合安全的 SemVer 文件命名规则"
    }
    if ($ArtifactSuffix -and $ArtifactSuffix -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]*$') {
        throw "-ArtifactSuffix 仅允许字母、数字、点、下划线与短横线"
    }
    $base = "${AppName}-${VersionName}"
    if ($OfficialRelease) {
        if ($ArtifactSuffix) { throw "正式包不接受 -ArtifactSuffix；文件名使用 App 名称-版本号" }
        return $base
    }
    $timestamp = $StartedAt.ToOffset([TimeSpan]::FromHours(8)).ToString("yyyyMMdd_HHmmss", [Globalization.CultureInfo]::InvariantCulture)
    $base += "_${timestamp}"
    if ($ArtifactSuffix) { $base += "_${ArtifactSuffix}" }
    return $base
}

function Test-ReleaseDestination([string]$Dist, [string]$Base) {
    foreach ($tail in @(".apk", ".sha256", "-mapping.txt", "-build.json")) {
        if (Test-Path -LiteralPath (Join-Path $Dist "$Base$tail")) {
            throw "dist/ 已有同名产物，拒绝覆盖；普通包稍后重试或添加 -ArtifactSuffix，正式包请先保留已有产物"
        }
    }
}

function Invoke-ReleasePackage {
    $version = Get-AppVersion
    $sdk = Resolve-AndroidSdk
    $releaseBuildStartedAt = [DateTimeOffset]::UtcNow
    $artifactBaseName = Get-ReleaseArtifactBase -AppName $version.AppName -VersionName $version.VersionName -StartedAt $releaseBuildStartedAt
    $dist = Join-Path $ProjectRoot "dist"
    Test-ReleaseDestination -Dist $dist -Base $artifactBaseName

    Write-Host "agenT Release 打包"
    Write-Host "  versionName : $($version.VersionName)"
    Write-Host "  versionCode : $($version.VersionCode)"
    Write-Host "  Android SDK : $sdk"
    Write-Host "  SigningRoot : $SigningRoot"
    Write-Host "  Clean       : $($Clean.IsPresent)"

    $metadataFile = Ensure-ReleaseSigningConfigured

    $pinnedFile = Join-Path $SigningRoot "pinned-certificate.sha256"
    if (Test-Path -LiteralPath $pinnedFile) {
        $pinned = (Get-Content -Raw -LiteralPath $pinnedFile).Trim().Replace(":", "").ToUpperInvariant()
        $metaProbe = Get-Content -Raw -LiteralPath $metadataFile | ConvertFrom-Json
        $current = ([string]$metaProbe.certificateSha256).Replace(":", "").ToUpperInvariant()
        if ($pinned -and $current -ne $pinned) {
            throw "签名证书与 pinned-certificate.sha256 不一致。请检查统一 release.keystore 与 pin；拒绝自动重绑，见 build.md"
        }
    }

    $signing = $null
    try {
        Write-Step "加载 Release 签名"
        $signing = Import-ReleaseSigningEnv -MetadataFile $metadataFile
        Write-Host "  keyAlias    : $($signing.Metadata.keyAlias)"
        Write-Host "  cert SHA256 : $($signing.Metadata.certificateSha256)"
        Write-Host "  source      : $($signing.Metadata.source)"

        # Release 默认离线；-Online 可联网。默认不 clean。
        $useOffline = -not $Online.IsPresent
        if ($Offline.IsPresent) { $useOffline = $true }

        # Invoke-Assemble 的返回值可能混入瞬态输出（如 lint-cache 清理），只取 .apk 路径元素
        # 注意 @() 必须包住整个管道：单个匹配经 Where-Object 返回标量字符串，直接 [-1] 会按字符索引
        $apkCandidates = @(Invoke-Assemble -Variant Release -DoClean:$Clean -DoOffline:$useOffline -DoSkipChecks:$SkipChecks |
            Where-Object { $_ -is [string] -and $_.EndsWith('.apk') })
        $apk = $apkCandidates[-1]
        if (-not $apk) { throw "未能取得 Release APK 路径" }

        Write-Step "校验 Release APK"
        Test-ReleaseApk `
            -Apk $apk `
            -MetadataFile $metadataFile `
            -ExpectedVersionCode $version.VersionCode `
            -ExpectedVersionName $version.VersionName `
            -Sdk $sdk

        Write-Step "导出产物到 dist/"
        Test-ReleaseDestination -Dist $dist -Base $artifactBaseName
        New-Item -ItemType Directory -Force -Path $dist | Out-Null

        $destination = Join-Path $dist "$artifactBaseName.apk"
        Copy-Item -LiteralPath $apk -Destination $destination

        $mapping = Join-Path $ProjectRoot "app\build\outputs\mapping\release\mapping.txt"
        if (Test-Path -LiteralPath $mapping) {
            Copy-Item -LiteralPath $mapping -Destination (Join-Path $dist "$artifactBaseName-mapping.txt")
        }

        $hash = Get-FileHash -LiteralPath $destination -Algorithm SHA256
        [IO.File]::WriteAllText((Join-Path $dist "$artifactBaseName.sha256"),
            "$($hash.Hash)  $([IO.Path]::GetFileName($destination))" + [Environment]::NewLine,
            ([System.Text.UTF8Encoding]::new($false)))

        [ordered]@{
            appName = $version.AppName
            packageName = "com.lamuier.agenT"
            versionCode = $version.VersionCode
            versionName = $version.VersionName
            apk = $destination
            apkSha256 = $hash.Hash
            certificateSha256 = [string]$signing.Metadata.certificateSha256
            builtAt = ([DateTimeOffset]::UtcNow).ToString("o")
            buildStartedAt = $releaseBuildStartedAt.ToString("o")
            officialRelease = [bool]$OfficialRelease
            artifactTimeZone = "Asia/Shanghai"
        } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $dist "$artifactBaseName-build.json") -Encoding utf8

        Write-Host ""
        Write-Host "打包完成" -ForegroundColor Green
        Write-Host "  APK     : $destination"
        Write-Host "  SHA-256 : $($hash.Hash)"
        Write-Host "  Size    : $([math]::Round((Get-Item $destination).Length / 1MB, 2)) MB"
    }
    finally {
        Clear-ReleaseSigningEnv
        if ($null -ne $signing) {
            $signing.PlainPassword = $null
        }
    }
}

# --- dispatch ---

if (($GenerateBaselineProfile -or $PerformanceReview) -and -not $Release) {
    throw "性能参数须同时使用 -Release"
}
if ($GenerateBaselineProfile -and $PerformanceReview) {
    throw "生成编译配置与性能测量须分次运行"
}

if (($OfficialRelease -or $ArtifactSuffix) -and -not $Release) {
    throw "-OfficialRelease / -ArtifactSuffix 须同时使用 -Release"
}
$modeCount = (@($Install, $Release, $SetupSigning) | Where-Object { $_ }).Count
if ($modeCount -gt 1) {
    throw "-Install、-Release、-SetupSigning 只能选其一"
}

if ($SetupSigning) {
    Invoke-SetupSigning
} elseif ($Release) {
    Invoke-ReleasePackage
} elseif ($Install) {
    Invoke-InstallDebug
} else {
    Invoke-DebugBuild
}
