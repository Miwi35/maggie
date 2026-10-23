<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\E2e;

use Maggie\Core\E2e\User\FixtureSlice;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Yaml\Yaml;

final class FixtureSliceTest extends TestCase
{
    private const FILES = [
        'Acme\Entity\User' => [
            'alice' => ['email' => 'a@x.test'],
            'bob' => ['email' => 'b@x.test'],
        ],
        'Acme\Entity\Shop' => [
            'alice_shop' => ['owner' => '@alice', 'tags' => ['mail me at someone@alice.test']],
            'bob_shop' => ['owner' => '@bob'],
        ],
        'Acme\Entity\Item' => [
            'alice_item' => ['shop' => '@alice_shop', 'unit' => '@unit'],
            'bob_item' => ['shop' => '@bob_shop'],
            'alice_item_{1..2}' => ['shop' => '@alice_shop'],
            'alice_item_link' => ['item' => '@alice_item_2'],
        ],
        'Acme\Entity\Unit' => [
            'unit' => ['name' => 'piece'],
            'unused_unit' => ['name' => 'bunch'],
        ],
    ];

    private string $file = '';

    protected function setUp(): void
    {
        $this->file = sys_get_temp_dir().'/fixture-slice-test-'.bin2hex(random_bytes(4)).'.yaml';
        file_put_contents($this->file, Yaml::dump(self::FILES, 4));
    }

    protected function tearDown(): void
    {
        @unlink($this->file);
    }

    public function testItKeepsWhatBelongsToTheRootsAndWhatItNeeds(): void
    {
        $slice = FixtureSlice::of([$this->file], ['alice']);

        self::assertSame(['alice'], array_keys($slice['Acme\Entity\User']));
        self::assertSame(['alice_shop'], array_keys($slice['Acme\Entity\Shop']));
        self::assertSame(['alice_item', 'alice_item_{1..2}', 'alice_item_link'], array_keys($slice['Acme\Entity\Item']));
        self::assertSame(['unit'], array_keys($slice['Acme\Entity\Unit']), 'A dependency stays, an unrelated entry goes.');
    }

    public function testAnEmailIsNotTakenForAReference(): void
    {
        $slice = FixtureSlice::of([$this->file], ['bob']);

        self::assertSame(['bob_shop'], array_keys($slice['Acme\Entity\Shop']));
        self::assertSame(['bob_item'], array_keys($slice['Acme\Entity\Item']));
    }

    public function testItCanBeRestrictedToSomeClasses(): void
    {
        $slice = FixtureSlice::of([$this->file], ['alice'], ['Acme\Entity\User']);

        self::assertSame(['Acme\Entity\User'], array_keys($slice));
    }

    public function testAnUnknownRootFails(): void
    {
        $this->expectException(\RuntimeException::class);

        FixtureSlice::of([$this->file], ['carol']);
    }

    public function testAnEntryTyingTwoUsersTogetherFails(): void
    {
        $tied = self::FILES;
        $tied['Acme\Entity\Shop']['shared_shop'] = ['owner' => '@alice', 'partner' => '@bob'];
        file_put_contents($this->file, Yaml::dump($tied, 4));

        $this->expectException(\RuntimeException::class);
        $this->expectExceptionMessage('bob');

        FixtureSlice::of([$this->file], ['alice']);
    }

    public function testDumpedSliceReadsBackTheSame(): void
    {
        $slice = FixtureSlice::of([$this->file], ['alice']);

        self::assertSame($slice, Yaml::parse(FixtureSlice::dump($slice)));
    }
}
