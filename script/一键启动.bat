@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title HybridFileXfer 电脑客户端启动器

cd /d "%~dp0"

echo ============================
echo  HybridFileXfer 电脑客户端
echo ============================
echo.

where java >nul 2>&1
if errorlevel 1 (
    echo [错误] 未检测到 Java，请先安装 JDK/JRE 并加入 PATH。
    echo 下载地址: https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html
    pause
    exit /b 1
)

if not exist "HybridFileXfer.jar" (
    echo [错误] 未找到 HybridFileXfer.jar，请确认它与启动脚本在同一目录。
    pause
    exit /b 1
)

:menu
echo.
echo 请选择连接方式：
echo   [1] ADB 有线连接
echo   [2] 无线网络连接（输入手机 IP）
echo   [3] 退出
set "choice="
set /p choice=请输入数字后回车：
if "%choice%"=="1" goto adb
if "%choice%"=="2" goto network
if "%choice%"=="3" exit /b 0
echo 输入无效，请重新选择。
goto menu

:adb
set "ADB=adb"
if exist "adb.exe" set "ADB=%~dp0adb.exe"

"%ADB%" version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到 adb，请将 adb.exe 放入本目录或加入 PATH。
    pause
    exit /b 1
)

echo 正在检测 ADB 设备...
set "count=0"
for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices') do (
    if "%%b"=="device" (
        set /a count+=1
        set "dev!count!=%%a"
    )
)

if %count%==0 (
    echo [错误] 未检测到已授权的 ADB 设备。
    echo 请连接手机并开启 USB 调试，然后在手机上允许此电脑的调试授权。
    pause
    exit /b 1
)

if %count%==1 (
    set "device=!dev1!"
    echo 检测到设备：!device!
    goto run_adb
)

echo 检测到多个设备：
for /l %%i in (1,1,%count%) do (
    echo   [%%i] !dev%%i!
)

:choose_device
set "sel="
set /p sel=请选择设备编号（1-%count%）:
set "num="
set /a num=%sel% 2>nul
if "%num%"=="" goto choose_device
if %num% lss 1 goto choose_device
if %num% gtr %count% goto choose_device
set "device=!dev%num%!"

:run_adb
echo.
echo 正在通过 ADB 连接设备 !device! ...
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "%~dp0HybridFileXfer.jar" -c adb -s !device!
echo.
echo 客户端已退出。
pause
exit /b 0

:network
set "ip="
set /p ip=请输入手机 IP 地址：
if "%ip%"=="" (
    echo IP 不能为空。
    goto network
)
echo.
echo 正在连接 %ip% ...
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "%~dp0HybridFileXfer.jar" -c %ip%
echo.
echo 客户端已退出。
pause
exit /b 0
