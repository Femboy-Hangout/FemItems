param(
    [ValidateSet("paper", "folia")]
    [string]$Platform = "paper",
    [string]$McVersion = "26.3",
    [int]$Port = 25565,
    [string]$Memory = "2G",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$UserAgent = "FemItems-DevServer/1.0 (https://github.com/Femboy-Hangout/FemItems)"

function Get-ProjectVersion {
    [xml]$pom = Get-Content (Join-Path $RepoRoot "pom.xml")
    return $pom.project.version.Trim()
}

function Ensure-ServerJar {
    param(
        [string]$TargetPlatform,
        [string]$Version,
        [string]$CacheDir
    )

    New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null
    $apiUrl = "https://fill.papermc.io/v3/projects/$TargetPlatform/versions/$Version/builds/latest"
    Write-Host "[$TargetPlatform] Querying PaperMC Fill v3 API: $apiUrl"
    $build = Invoke-RestMethod -Headers @{ "User-Agent" = $UserAgent } -Uri $apiUrl
    $download = $build.downloads."server:default"
    if (-not $download) {
        throw "[$TargetPlatform] Could not locate server:default download in Fill API response."
    }

    $jarName = $download.name
    $jarUrl = $download.url
    $expectedSha256 = $download.checksums.sha256.ToLowerInvariant()
    $jarPath = Join-Path $CacheDir $jarName

    if (-not (Test-Path $jarPath) -or ((Get-FileHash -Algorithm SHA256 -Path $jarPath).Hash.ToLowerInvariant() -ne $expectedSha256)) {
        Write-Host "[$TargetPlatform] Downloading $jarName..."
        Invoke-WebRequest -Headers @{ "User-Agent" = $UserAgent } -Uri $jarUrl -OutFile $jarPath
        $actualSha256 = (Get-FileHash -Algorithm SHA256 -Path $jarPath).Hash.ToLowerInvariant()
        if ($actualSha256 -ne $expectedSha256) {
            Remove-Item -Force $jarPath -ErrorAction SilentlyContinue
            throw "[$TargetPlatform] SHA-256 verification failed for $jarName."
        }
    } else {
        Write-Host "[$TargetPlatform] Using cached $jarName."
    }

    return $jarPath
}

Push-Location $RepoRoot
try {
    if (-not $SkipBuild) {
        Write-Host "Building FemItems with Maven Wrapper..."
        & (Join-Path $RepoRoot "mvnw.cmd") -B verify
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE"
        }
    }

    $pluginVersion = Get-ProjectVersion
    $pluginJar = Join-Path $RepoRoot "target\femitems-$pluginVersion.jar"
    if (-not (Test-Path $pluginJar)) {
        throw "Built plugin JAR not found at $pluginJar"
    }

    $testRoot = Join-Path $RepoRoot ".mc-test"
    $cacheDir = Join-Path $testRoot "cache"
    $serverDir = Join-Path $testRoot "run-$Platform"
    $pluginsDir = Join-Path $serverDir "plugins"

    $serverJar = Ensure-ServerJar -TargetPlatform $Platform -Version $McVersion -CacheDir $cacheDir

    New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null
    Get-ChildItem -Path $pluginsDir -Filter "femitems-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item -Force -Path $pluginJar -Destination (Join-Path $pluginsDir (Split-Path $pluginJar -Leaf))

    $eulaPath = Join-Path $serverDir "eula.txt"
    if (-not (Test-Path $eulaPath)) {
        Set-Content -Path $eulaPath -Value "eula=true" -Encoding ASCII
    }

    $propsPath = Join-Path $serverDir "server.properties"
    if (-not (Test-Path $propsPath)) {
        Set-Content -Path $propsPath -Encoding ASCII -Value @"
online-mode=false
white-list=false
enforce-whitelist=false
server-port=$Port
motd=FemItems Dev Server ($Platform $McVersion)
"@
    }

    Write-Host "Launching interactive $Platform $McVersion server on port $Port (type 'stop' to exit)..."
    Set-Location $serverDir
    & java "-Xms1G" "-Xmx$Memory" -jar $serverJar --nogui
} finally {
    Pop-Location
}
