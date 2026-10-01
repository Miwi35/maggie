<?php

// PHPStan does not expand globs in `paths`: list every module here so a new one is analysed by default.
return [
    'parameters' => [
        'paths' => glob(__DIR__.'/modules/*/src', GLOB_ONLYDIR) ?: [],
    ],
];
