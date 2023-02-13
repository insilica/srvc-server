{
  description = "srvc server";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    flake-compat = {
      url = "github:edolstra/flake-compat";
      flake = false;
    };
    clj-nix = {
      url = "github:jlesquembre/clj-nix";
      inputs.flake-utils.follows = "flake-utils";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    srvc = {
      url = "github:insilica/rs-srvc/v0.14.0";
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.flake-utils.follows = "flake-utils";
    };
  };
  outputs = { self, nixpkgs, flake-utils, clj-nix, srvc, ... }@inputs:
    flake-utils.lib.eachDefaultSystem (system:
      with import nixpkgs { inherit system; };
      let
        cljpkgs = clj-nix.packages."${system}";
        srvc-server-bin = cljpkgs.mkCljBin {
          projectSrc = ./.;
          name = "srvc-server";
          main-ns = "srvc.server";
          jdkRunner = pkgs.jdk17_headless;
        };
        # Strips out unused JDK code for a smaller binary
        srvc-server = cljpkgs.customJdk { cljDrv = srvc-server-bin; };
      in {
        packages = {
          inherit srvc-server srvc-server-bin;
          default = srvc-server;
        };
        devShells.default = mkShell {
          buildInputs = [
            clj-nix.packages.${system}.deps-lock
            clojure
            git
            jdk
            perl
            rlwrap
            srvc.packages.${system}.default
          ];
        };
      });
}
