{
  description = "srvc server";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    srvc = {
      url = "github:insilica/rs-srvc";
      inputs.nixpkgs.follows = "nixpkgs";
      inputs.flake-utils.follows = "flake-utils";
    };
  };
  outputs = { self, nixpkgs, flake-utils, srvc, ... }@inputs:
    flake-utils.lib.eachDefaultSystem (system:
      with import nixpkgs { inherit system; };
      let
        srvc-server = stdenv.mkDerivation {
          name = "srvc-server";
          src = ./.;

          installPhase = ''
            mkdir -p $out
          '';
        };
      in {
        packages = {
          inherit srvc-server;
          default = srvc-server;
        };
        devShells.default = mkShell {
          buildInputs = [
            clojure
            jdk
            perl
            rlwrap
            srvc.packages.${system}.default
          ];
        };
      });
}
