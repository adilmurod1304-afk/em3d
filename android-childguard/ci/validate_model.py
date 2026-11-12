#!/usr/bin/env python3
"""TFLite yuz-modelini CI'da tekshiradi: haqiqatan yuklanadimi va
kirish [1,H,W,3], chiqish [1,N] shaklida ekanini tasdiqlaydi."""
import sys

def main(path: str) -> int:
    try:
        try:
            from ai_edge_litert.interpreter import Interpreter
        except Exception:
            from tensorflow.lite.python.interpreter import Interpreter  # fallback
        it = Interpreter(model_path=path)
        it.allocate_tensors()
        i = it.get_input_details()[0]
        o = it.get_output_details()[0]
        ish = [int(x) for x in i["shape"]]
        osh = [int(x) for x in o["shape"]]
        print("input:", ish, i["dtype"], "| output:", osh, o["dtype"])
        if len(ish) != 4 or ish[-1] != 3:
            print("INVALID: kirish [1,H,W,3] emas")
            return 1
        if len(osh) != 2 or osh[-1] < 64:
            print("INVALID: chiqish [1,N>=64] emas")
            return 1
        print("VALID")
        return 0
    except Exception as e:
        print("INVALID:", repr(e))
        return 1

if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
