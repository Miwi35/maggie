<?php

namespace Maggie\Finance\Tests\Specification;

use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Specification\HasDetectionRelevantChange;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

class HasDetectionRelevantChangeTest extends TestCase
{
    /** @return iterable<string, array{array<string, array{mixed, mixed}>, list<string>}> */
    public static function changes(): iterable
    {
        yield 'label' => [['label' => ['A', 'B']], ['label']];
        yield 'amount' => [['amountCents' => [-100, -200]], ['amountCents']];
        yield 'date' => [['bookedAt' => [new \DateTimeImmutable('2026-09-01'), new \DateTimeImmutable('2026-09-02')]], ['bookedAt']];
        yield 'account' => [['account' => ['checking', 'savings']], ['account']];
        yield 'several, in the order the rules read them' => [['bookedAt' => [new \DateTimeImmutable('2026-09-01'), new \DateTimeImmutable('2026-09-02')], 'label' => ['A', 'B']], ['label', 'bookedAt']];
        yield 'a relevant one among others' => [['isExceptional' => [false, true], 'label' => ['A', 'B']], ['label']];
        yield 'category only' => [['category' => [null, 'food'], 'categorySource' => ['none', 'rule']], []];
        yield 'pairing only' => [['transferKind' => ['none', 'internal'], 'counterpart' => [null, 'x']], []];
        yield 'same date rewritten' => [['bookedAt' => [new \DateTimeImmutable('2026-09-01'), new \DateTimeImmutable('2026-09-01')]], []];
        yield 'nothing' => [[], []];
    }

    /**
     * @param array<string, array{mixed, mixed}> $changeSet
     * @param list<string>                       $expected
     */
    #[DataProvider('changes')]
    public function testOnlyWhatTheRulesAndDetectionsReadCounts(array $changeSet, array $expected): void
    {
        $specification = new HasDetectionRelevantChange($changeSet);

        self::assertSame([] !== $expected, $specification->isSatisfiedBy(new Transaction()));
        self::assertSame($expected, $specification->changedFields());
    }
}
