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
      url = "github:insilica/rs-srvc";
      inputs.flake-utils.follows = "flake-utils";
      inputs.nixpkgs.follows = "nixpkgs";
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
      in {
        packages = {
          inherit srvc-server-bin;
          default = srvc-server-bin;
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
