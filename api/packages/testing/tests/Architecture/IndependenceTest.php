<?php

declare(strict_types=1);

namespace PsychedLabs\Testing\Tests\Architecture;

use PHPUnit\Framework\TestCase;

final class IndependenceTest extends TestCase
{
    private const FORBIDDEN = ['Maggie\\', 'App\\', 'PsychedCms\\'];

    public function testThePackageDependsOnNothingFromTheApplications(): void
    {
        $offences = [];
        $files = new \RecursiveIteratorIterator(new \RecursiveDirectoryIterator(\dirname(__DIR__, 2).'/src', \FilesystemIterator::SKIP_DOTS));

        foreach ($files as $file) {
            if ('php' !== $file->getExtension()) {
                continue;
            }
            foreach (token_get_all((string) file_get_contents($file->getPathname())) as $token) {
                if (!\is_array($token) || !\in_array($token[0], [\T_NAME_QUALIFIED, \T_NAME_FULLY_QUALIFIED, \T_STRING], true)) {
                    continue;
                }
                $name = ltrim($token[1], '\\').'\\';
                foreach (self::FORBIDDEN as $prefix) {
                    if (str_starts_with($name, $prefix)) {
                        $offences[] = $file->getFilename().': '.$token[1];
                    }
                }
            }
        }

        self::assertSame([], $offences, 'psychedlabs/testing must not reference Maggie, App or PsychedCms.');
    }

    public function testComposerRequiresNothingFromTheApplications(): void
    {
        $composer = json_decode((string) file_get_contents(\dirname(__DIR__, 2).'/composer.json'), true, 512, \JSON_THROW_ON_ERROR);

        foreach (array_keys($composer['require'] + ($composer['require-dev'] ?? [])) as $dependency) {
            self::assertDoesNotMatchRegularExpression('#^(maggie|psychedcms)/#', $dependency);
        }
    }
}
