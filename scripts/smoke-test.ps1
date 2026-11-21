param(
    [ValidateSet("paper", "folia", "all")]
    [string]$Platform = "all",
    [string]$McVersion = "26.3",
    [switch]$SkipBuild,
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$UserAgent = "FemItems-SmokeTest/1.0 (https://github.com/Femboy-Hangout/FemItems)"

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

    $needsDownload = $true
    if (Test-Path $jarPath) {
        $actualSha256 = (Get-FileHash -Algorithm SHA256 -Path $jarPath).Hash.ToLowerInvariant()
        if ($actualSha256 -eq $expectedSha256) {
            Write-Host "[$TargetPlatform] Using cached $jarName (SHA-256 verified)."
            $needsDownload = $false
        } else {
            Write-Host "[$TargetPlatform] Cached $jarName checksum mismatch; re-downloading."
        }
    }

    if ($needsDownload) {
        Write-Host "[$TargetPlatform] Downloading $jarName..."
        Invoke-WebRequest -Headers @{ "User-Agent" = $UserAgent } -Uri $jarUrl -OutFile $jarPath
        $actualSha256 = (Get-FileHash -Algorithm SHA256 -Path $jarPath).Hash.ToLowerInvariant()
        if ($actualSha256 -ne $expectedSha256) {
            Remove-Item -Force $jarPath -ErrorAction SilentlyContinue
            throw "[$TargetPlatform] SHA-256 verification failed for $jarName (expected $expectedSha256, got $actualSha256)."
        }
        Write-Host "[$TargetPlatform] Downloaded and verified $jarName."
    }

    return $jarPath
}

function Invoke-PlatformSmokeTest {
    param(
        [string]$TargetPlatform,
        [string]$Version,
        [string]$PluginJar,
        [string]$PluginVersion,
        [int]$Port
    )

    $testRoot = Join-Path $RepoRoot ".mc-test"
    $cacheDir = Join-Path $testRoot "cache"
    $serverDir = Join-Path $testRoot "smoke-$TargetPlatform"
    $pluginsDir = Join-Path $serverDir "plugins"
    $logsDir = Join-Path $serverDir "logs"

    $serverJar = Ensure-ServerJar -TargetPlatform $TargetPlatform -Version $Version -CacheDir $cacheDir

    New-Item -ItemType Directory -Force -Path $pluginsDir | Out-Null
    Get-ChildItem -Path $pluginsDir -Filter "femitems-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
    $pluginConfigDir = Join-Path $pluginsDir "FemItems"
    if (Test-Path $pluginConfigDir) {
        Remove-Item -Recurse -Force $pluginConfigDir
    }
    if (Test-Path $logsDir) {
        Remove-Item -Recurse -Force $logsDir
    }

    Copy-Item -Force -Path $PluginJar -Destination (Join-Path $pluginsDir (Split-Path $PluginJar -Leaf))

    Set-Content -Path (Join-Path $serverDir "eula.txt") -Value "eula=true" -Encoding ASCII
    Set-Content -Path (Join-Path $serverDir "server.properties") -Encoding ASCII -Value @"
online-mode=false
level-type=minecraft\:flat
generate-structures=false
allow-nether=false
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
view-distance=2
simulation-distance=2
server-port=$Port
pause-when-empty-seconds=0
"@
    Set-Content -Path (Join-Path $serverDir "bukkit.yml") -Encoding ASCII -Value @"
settings:
  allow-end: false
"@

    $stdoutLog = Join-Path $serverDir "smoke-stdout.log"
    $stderrLog = Join-Path $serverDir "smoke-stderr.log"
    Remove-Item -Force $stdoutLog, $stderrLog -ErrorAction SilentlyContinue

    Write-Host "[$TargetPlatform] Starting headless $TargetPlatform $Version server with $(Split-Path $PluginJar -Leaf)..."

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = "java"
    $psi.Arguments = "-Xms512M -Xmx1536M -jar `"$serverJar`" --nogui"
    $psi.WorkingDirectory = $serverDir
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true

    $proc = [System.Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    [void]$proc.Start()

    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()

    $latestLog = Join-Path $logsDir "latest.log"
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $started = $false

    try {
        while ((Get-Date) -lt $deadline) {
            if ($proc.HasExited) {
                break
            }
            if (Test-Path $latestLog) {
                $content = Get-Content -Path $latestLog -Raw -ErrorAction SilentlyContinue
                if ($content -and $content -match 'Done \([0-9.,]+s\)!') {
                    $started = $true
                    break
                }
            }
            Start-Sleep -Milliseconds 500
        }

        if (-not $started) {
            if (-not $proc.HasExited) {
                $proc.Kill($true)
            }
            $logExcerpt = if (Test-Path $latestLog) { Get-Content -Path $latestLog -Tail 80 -ErrorAction SilentlyContinue | Out-String } else { "(no latest.log produced)" }
            throw "[$TargetPlatform] Server did not reach 'Done' within ${TimeoutSeconds}s.`n$logExcerpt"
        }

        Write-Host "[$TargetPlatform] Server started. Executing smoke test commands..."
        $proc.StandardInput.WriteLine("femitems reload")
        $proc.StandardInput.Flush()
        Start-Sleep -Seconds 3

        $proc.StandardInput.WriteLine("femitems debug")
        $proc.StandardInput.Flush()
        Start-Sleep -Seconds 1

        $proc.StandardInput.WriteLine("femitems debug")
        $proc.StandardInput.Flush()
        Start-Sleep -Seconds 1

        $proc.StandardInput.WriteLine("stop")
        $proc.StandardInput.Flush()

        if (-not $proc.WaitForExit(60000)) {
            $proc.Kill($true)
            throw "[$TargetPlatform] Server failed to stop cleanly within 60s."
        }
    } finally {
        if (-not $proc.HasExited) {
            $proc.Kill($true)
        }
        $proc.WaitForExit(10000) | Out-Null
        Set-Content -Path $stdoutLog -Value $outTask.GetAwaiter().GetResult() -Encoding UTF8
        Set-Content -Path $stderrLog -Value $errTask.GetAwaiter().GetResult() -Encoding UTF8
    }

    $exitCode = $proc.ExitCode
    if ($exitCode -ne 0) {
        throw "[$TargetPlatform] Server exited with non-zero code: $exitCode"
    }

    if (-not (Test-Path $latestLog)) {
        throw "[$TargetPlatform] Missing logs/latest.log after server run."
    }

    $logText = Get-Content -Path $latestLog -Raw
    $expectedPlatformLabel = if ($TargetPlatform -eq "folia") { "Folia" } else { "Paper" }

    $requiredPatterns = @(
        "Initializing FemItems $([regex]::Escape($PluginVersion)) on $expectedPlatformLabel\.",
        "Loaded 5 mob rules and 0 recipes\.",
        "\[FemItems\] Config reloaded\.",
        "\[FemItems\] Mob death debug enabled",
        "\[FemItems\] Mob death debug disabled\."
    )

    foreach ($pattern in $requiredPatterns) {
        if ($logText -notmatch $pattern) {
            throw "[$TargetPlatform] Smoke test assertion failed - missing expected log pattern: $pattern`n--- latest.log ---`n$logText"
        }
    }

    $forbiddenPatterns = @(
        "Could not load plugin",
        "Error occurred while enabling FemItems",
        "Error occurred while disabling FemItems",
        "UnsupportedOperationException",
        "(?i)exception[\s\S]{0,200}dev\.femitems",
        "(?i)dev\.femitems[\s\S]{0,200}exception"
    )

    foreach ($pattern in $forbiddenPatterns) {
        if ($logText -match $pattern) {
            throw "[$TargetPlatform] Smoke test assertion failed - detected error pattern: $pattern`n--- latest.log ---`n$logText"
        }
    }

    Write-Host "[$TargetPlatform] SMOKE TEST PASSED (FemItems $PluginVersion on $expectedPlatformLabel $Version)." -ForegroundColor Green
}

Push-Location $RepoRoot
try {
    if (-not $SkipBuild) {
        Write-Host "Building plugin with Maven Wrapper..."
        & (Join-Path $RepoRoot "mvnw.cmd") -B clean verify
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE"
        }
    }

    $pluginVersion = Get-ProjectVersion
    $pluginJar = Join-Path $RepoRoot "target\femitems-$pluginVersion.jar"
    if (-not (Test-Path $pluginJar)) {
        throw "Built plugin JAR not found at $pluginJar"
    }

    $targets = if ($Platform -eq "all") { @("paper", "folia") } else { @($Platform) }
    $ports = @{ paper = 25595; folia = 25596 }

    foreach ($target in $targets) {
        Invoke-PlatformSmokeTest `
            -TargetPlatform $target `
            -Version $McVersion `
            -PluginJar $pluginJar `
            -PluginVersion $pluginVersion `
            -Port $ports[$target]
    }
} finally {
    Pop-Location
}
