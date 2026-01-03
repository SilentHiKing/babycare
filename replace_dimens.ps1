$targetPath = "e:\Code\Android\babycare"
$xmlFiles = Get-ChildItem -Path $targetPath -Recurse -Filter "*.xml"

foreach ($file in $xmlFiles) {
    $content = Get-Content $file.FullName -Raw
    $originalContent = $content

    # Replace @dimen/dp_10 to 10dp
    $content = $content -replace '@dimen/dp_(\d+)', '$1dp'
    # Replace @dimen/sp_14 to 14sp
    $content = $content -replace '@dimen/sp_(\d+)', '$1sp'
    # Replace @dimen/dp_10_5 to 10.5dp (just in case)
    $content = $content -replace '@dimen/dp_(\d+)_(\d+)', '$1.$2dp'
    # Replace @dimen/sp_10_5 to 10.5sp
    $content = $content -replace '@dimen/sp_(\d+)_(\d+)', '$1.$2sp'

    if ($content -ne $originalContent) {
        Write-Host "Updating $($file.FullName)"
        $content | Set-Content $file.FullName -Encoding UTF8
    }
}
