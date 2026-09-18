$path = 'E:\MySkills\app\src\main\java\com\example\myskills\fragments\HanziWriterFragment2.java'
$b = [System.IO.File]::ReadAllBytes($path)
Write-Output ("First4Bytes: " + ($b[0..3] -join ","))
Write-Output ("TotalBytes: " + $b.Length)
$utf8 = [System.Text.Encoding]::UTF8.GetString($b)
Write-Output ("Utf8ContainsSwitchCharacter: " + $utf8.Contains("switchCharacter"))
Write-Output ("Utf8ContainsCRLF: " + $utf8.Contains([char]13 + [char]10))
$uni = [System.Text.Encoding]::Unicode.GetString($b)
Write-Output ("UnicodeContainsSwitchCharacter: " + $uni.Contains("switchCharacter"))
$zeroCount = 0
foreach ($byte in $b) { if ($byte -eq 0) { $zeroCount++ } }
Write-Output ("ZeroByteCount: " + $zeroCount)
