<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Tests\Bank\BankStatementTestTrait;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpClient\Response\MockResponse;

class ReadBankStatementTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use BankStatementTestTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->makeKey();
        $this->loadFixtures(__DIR__.'/../Bank/fixtures/account.yaml', __DIR__.'/../Bank/fixtures/other_user_account.yaml');
    }

    protected function tearDown(): void
    {
        $this->dropKey();
        parent::tearDown();
    }

    private function period(): array
    {
        return [new \DateTimeImmutable('2026-09-01'), new \DateTimeImmutable('2026-09-30')];
    }

    public function testReadsTheRequestedPeriodWithoutWriting(): void
    {
        $this->connectAccount();
        $reader = $this->readerOn(fn () => $this->pageOf($this->september()));
        [$from, $to] = $this->period();

        $read = $reader->read($this->getFixture('test_user'), (string) $this->getFixture('checking')->getId(), $from, $to);

        self::assertSame('ok', $read['status']);
        self::assertCount(5, $read['rows']);
        self::assertCount(1, $this->requested);
        self::assertStringContainsString('date_from=2026-09-01', $this->requested[0]);
        self::assertStringContainsString('date_to=2026-09-30', $this->requested[0]);
        self::assertSame(0, self::getContainer()->get('doctrine.orm.entity_manager')->getRepository(Transaction::class)->count([]));
    }

    public function testAPeriodRefusalIsHistoryUnavailable(): void
    {
        $this->connectAccount();
        $reader = $this->readerOn(fn () => new MockResponse(json_encode(['message' => 'date_from is too old'], JSON_THROW_ON_ERROR), ['http_code' => 422]));
        [$from, $to] = $this->period();

        $read = $reader->read($this->getFixture('test_user'), (string) $this->getFixture('checking')->getId(), $from, $to);

        self::assertSame('history_unavailable', $read['status']);
        self::assertSame('date_from is too old', $read['message']);
        self::assertSame([], $read['rows']);
    }

    public function testRateLimitIsReported(): void
    {
        $this->connectAccount();
        $reader = $this->readerOn(fn () => new MockResponse(json_encode(['error' => 'ASPSP_RATE_LIMIT_EXCEEDED'], JSON_THROW_ON_ERROR), ['http_code' => 429]));
        [$from, $to] = $this->period();

        $read = $reader->read($this->getFixture('test_user'), (string) $this->getFixture('checking')->getId(), $from, $to);

        self::assertSame('rate_limited', $read['status']);
        self::assertSame([], $read['rows']);
    }

    public function testAnotherUsersAccountIsRefused(): void
    {
        $this->connectAccount();
        $reader = $this->readerOn(fn () => $this->pageOf([]));
        [$from, $to] = $this->period();

        $this->expectException(\InvalidArgumentException::class);
        $reader->read($this->getFixture('test_user'), (string) $this->getFixture('other_account')->getId(), $from, $to);
    }

    public function testAnAccountWithoutABankConnectionIsRefused(): void
    {
        $reader = $this->readerOn(fn () => $this->pageOf([]));
        [$from, $to] = $this->period();

        $this->expectException(\InvalidArgumentException::class);
        $reader->read($this->getFixture('test_user'), (string) $this->getFixture('checking')->getId(), $from, $to);
    }
}
