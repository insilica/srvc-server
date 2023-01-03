{ stdenv }:

stdenv.mkDerivation rec {
  pname = "srvc-server";
  version = "0.1.0";

  src = ./src;

  buildInputs = [ ];

  installPhase = ''
    mkdir -p $out/src
    cp -r . $out/src
  '';
}
