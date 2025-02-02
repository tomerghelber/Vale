
echo Downloading minimal LLVM...

powershell -c "$ProgressPreference = 'SilentlyContinue' ; Invoke-WebRequest -Uri 'https://github.com/Verdagon/LLVM13WinMinimal/releases/download/v1.1/llvm-project-llvmorg-13.0.1.zip' -OutFile '%temp%\llvm-install-minimal.zip'"
mkdir %1
tar xf "%temp%\llvm-install-minimal.zip" -C %1

echo Downloading bootstrapping Vale compiler...

powershell -c "$ProgressPreference = 'SilentlyContinue' ; Invoke-WebRequest -Uri 'https://github.com/ValeLang/Vale/releases/download/v0.2.0.0/Vale-Windows-0.2.0.7.zip' -OutFile '%temp%\BootstrappingValeCompiler.zip'"
mkdir %2
tar xf "%temp%\BootstrappingValeCompiler.zip" -C %2
