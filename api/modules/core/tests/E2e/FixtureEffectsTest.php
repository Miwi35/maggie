<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\E2e;

use Maggie\Core\E2e\Fixture\FixtureEffects;
use Maggie\Core\E2e\Fixture\FixtureEffectsInterface;
use PHPUnit\Framework\TestCase;

class FixtureEffectsTest extends TestCase
{
    public function testTheWorkRunsInsideEveryModulesSuspension(): void
    {
        $log = new \ArrayObject();
        $module = static fn (string $name): FixtureEffectsInterface => new class($name, $log) implements FixtureEffectsInterface {
            public function __construct(private readonly string $name, private readonly \ArrayObject $log)
            {
            }

            public function withoutEffects(\Closure $work): mixed
            {
                $this->log[] = $this->name.' suspended';
                try {
                    return $work();
                } finally {
                    $this->log[] = $this->name.' resumed';
                }
            }
        };

        $result = (new FixtureEffects([$module('finance'), $module('other')]))->withoutEffects(static function () use ($log): string {
            $log[] = 'work';

            return 'loaded';
        });

        self::assertSame('loaded', $result);
        self::assertSame(['other suspended', 'finance suspended', 'work', 'finance resumed', 'other resumed'], $log->getArrayCopy());
    }

    public function testWithNoModuleTheWorkJustRuns(): void
    {
        self::assertSame(3, (new FixtureEffects([]))->withoutEffects(static fn (): int => 3));
    }
}
