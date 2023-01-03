{ pkgs ? import <nixpkgs> { } }:
let target = pkgs.stdenv.targetPlatform;
in with pkgs;
mkShell { buildInputs = [ babashka clojure jdk perl rlwrap srvc ]; }
