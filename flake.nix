{
  description = "Parroglott: Amazon's Fire OS Ivona voices as an Android text-to-speech engine";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      buildToolsVersion = "35.0.0";
      android = pkgs.androidenv.composeAndroidPackages {
        # 36 for the manifest's pageSizeCompat attribute.
        platformVersions = [ "36" ];
        buildToolsVersions = [ buildToolsVersion ];
        includeNDK = false;
        includeEmulator = false;
        includeSystemImages = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";

      # A headless x86_64 Android for testing without a phone: Google APIs
      # images translate arm64 apps, and Amazon's engine is arm64 only.
      emulatorPackages = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "35" ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis" ];
        abiVersions = [ "x86_64" ];
      };
      emulatorSdk = "${emulatorPackages.androidsdk}/libexec/android-sdk";
      # Android 17 on a 16 KB-page kernel: Amazon's engine and its 2025 voices
      # are linked for 4 KB pages, so this is where that shows.
      emulator16kPackages = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "37.0" ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis_ps16k" ];
        abiVersions = [ "x86_64" ];
      };
      emulator16kSdk = "${emulator16kPackages.androidsdk}/libexec/android-sdk";

      python = pkgs.python3.withPackages (ps: [ ps.brotli ]);
    in
    {
      devShells.${system} = {
        default = pkgs.mkShell {
          packages = [
            python
            android.androidsdk
            pkgs.jdk17
            pkgs.gradle
            pkgs.android-tools
            pkgs.llvmPackages.llvm
            pkgs.whisper-cpp
            pkgs.sox
            pkgs.jq
          ];

          # android-tools brings its own Python; name the one with brotli outright.
          PG_PYTHON = "${python}/bin/python3";
          ANDROID_HOME = sdk;
          ANDROID_SDK_ROOT = sdk;
          JAVA_HOME = pkgs.jdk17.home;
          # AGP otherwise fetches an aapt2 from Maven that cannot run on NixOS.
          GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2";
        };

        emulator = pkgs.mkShell {
          packages = [ emulatorPackages.androidsdk pkgs.android-tools ];
          ANDROID_HOME = emulatorSdk;
          ANDROID_SDK_ROOT = emulatorSdk;
        };

        emulator16k = pkgs.mkShell {
          packages = [ emulator16kPackages.androidsdk pkgs.android-tools ];
          ANDROID_HOME = emulator16kSdk;
          ANDROID_SDK_ROOT = emulator16kSdk;
        };
      };
    };
}
