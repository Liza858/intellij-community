try:
    from _pydevd_frame_eval.pydevd_frame_evaluator import frame_eval_func, stop_frame_eval
except ImportError:
    try:
        import struct
        import sys
        try:
            is_64bits = sys.maxsize > 2**32
        except:
            # In Jython this call fails, but this is Ok, we don't support Jython for speedups anyways.
            raise ImportError
        plat = '32'
        if is_64bits:
            plat = '64'

        # We also accept things as:
        #
        # _pydevd_bundle.pydevd_cython_win32_27_32
        # _pydevd_bundle.pydevd_cython_win32_34_64
        #
        # to have multiple pre-compiled pyds distributed along the IDE
        # (generated by build_tools/build_binaries_windows.py).

        mod_name = 'pydevd_frame_evaluator_%s_%s%s_%s' % (sys.platform, sys.version_info[0], sys.version_info[1], plat)
        check_name = '_pydevd_frame_eval.%s' % (mod_name,)
        mod = __import__(check_name)
        mod = getattr(mod, mod_name)
        frame_eval_func, stop_frame_eval = mod.frame_eval_func, mod.stop_frame_eval
    except ImportError:
        raise