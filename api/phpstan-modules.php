<?php

// PHPStan does not expand globs in `paths`: list every module here so a new one is analysed by default.
return [
    'parameters' => [
        'paths' => array_merge(
            glob(__DIR__.'/modules/*/src', GLOB_ONLYDIR) ?: [],
            glob(__DIR__.'/packages/*/src', GLOB_ONLYDIR) ?: [],
        ),
    ],
];
