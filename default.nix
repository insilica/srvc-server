{ sources ? import ./nix/sources.nix, pkgs ? import sources.nixpkgs { } }:
let jdk = pkgs.openjdk17;
in with pkgs;
mkShell {
  buildInputs = [ (clojure.override { jdk = jdk; }) jdk perl rlwrap ];
}
