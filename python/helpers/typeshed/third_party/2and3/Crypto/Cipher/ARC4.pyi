# Stubs for Crypto.Cipher.ARC4 (Python 3.5)
#
# NOTE: This dynamically typed stub was automatically generated by stubgen.

from typing import Any, Union, Text

__revision__ = ...  # type: str

class ARC4Cipher:
    block_size = ...  # type: int
    key_size = ...  # type: int
    def __init__(self, key: Union[bytes, Text], *args, **kwargs) -> None: ...
    def encrypt(self, plaintext): ...
    def decrypt(self, ciphertext): ...

def new(key: Union[bytes, Text], *args, **kwargs) -> ARC4Cipher: ...

block_size = ...  # type: int
key_size = ...  # type: int
