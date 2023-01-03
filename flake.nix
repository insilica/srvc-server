{
  description = "srvc server";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        default = pkgs.callPackage ./default.nix { };
      in with pkgs; {
        packages = { inherit default; };
        devShells.default = import ./shell.nix { inherit pkgs; };
      });
}
