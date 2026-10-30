<?php

declare(strict_types=1);

namespace PsychedLabs\Testing\Tests\Contract;

use PHPUnit\Framework\AssertionFailedError;
use PHPUnit\Framework\TestCase;
use PsychedLabs\Testing\Contract\ContractSnapshotTrait;

final class ContractSnapshotTraitTest extends TestCase
{
    use ContractSnapshotTrait;

    private static string $dir;

    protected static function contractDir(): string
    {
        return self::$dir;
    }

    protected function setUp(): void
    {
        self::$dir = sys_get_temp_dir().'/contract-'.bin2hex(random_bytes(6));
        mkdir(self::$dir);
        unset($_SERVER['UPDATE_CONTRACT']);
    }

    protected function tearDown(): void
    {
        foreach (glob(self::$dir.'/*') ?: [] as $file) {
            unlink($file);
        }
        rmdir(self::$dir);
        unset($_SERVER['UPDATE_CONTRACT']);
    }

    public function testItMatchesAnIdenticalSnapshot(): void
    {
        file_put_contents(self::$dir.'/a.json', "{\n    \"x\": 1\n}\n");

        $this->assertMatchesContract('a.json', ['x' => 1], 'why');
    }

    public function testItFailsWhenTheSnapshotDiffers(): void
    {
        file_put_contents(self::$dir.'/a.json', "{\n    \"x\": 1\n}\n");

        $this->expectException(AssertionFailedError::class);
        $this->expectExceptionMessage('contract/a.json is out of date');

        $this->assertMatchesContract('a.json', ['x' => 2], 'why');
    }

    public function testItFailsWhenTheSnapshotIsMissing(): void
    {
        $this->expectException(AssertionFailedError::class);
        $this->expectExceptionMessage('is missing');

        $this->assertMatchesContract('nope.json', [], 'why');
    }

    public function testItWritesTheSnapshotWhenUpdating(): void
    {
        $_SERVER['UPDATE_CONTRACT'] = '1';

        $this->assertMatchesContract('sub/b.json', ['y' => 'é/'], 'why');

        self::assertSame("{\n    \"y\": \"é/\"\n}\n", file_get_contents(self::$dir.'/sub/b.json'));
        unlink(self::$dir.'/sub/b.json');
        rmdir(self::$dir.'/sub');
    }
}
