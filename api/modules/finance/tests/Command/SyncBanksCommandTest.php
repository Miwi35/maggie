<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Command;

use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserRepository;
use Maggie\Finance\Command\SyncBanksCommand;
use Maggie\Finance\Repository\BankConnectionRepository;
use Maggie\Finance\UseCase\SyncBankAccounts;
use PHPUnit\Framework\TestCase;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * What the cron relies on: it syncs every owner, and one owner going wrong
 * neither starves the next nor looks like a green run.
 */
final class SyncBanksCommandTest extends TestCase
{
    public function testAFailingOwnerDoesNotStopTheNextOneFromSyncing(): void
    {
        $first = $this->user('first@example.com');
        $second = $this->user('second@example.com');

        $sync = $this->createStub(SyncBankAccounts::class);
        $seen = [];
        $sync->method('execute')->willReturnCallback(function (User $user) use (&$seen, $first) {
            $seen[] = $user->getEmail();
            if ($user === $first) {
                // Not a RuntimeException: what a failed flush would throw.
                throw new \LogicException('flush failed');
            }

            return $this->syncResult('synced');
        });

        $tester = $this->tester($sync, owners: [$first, $second]);
        $tester->execute(['--write' => true]);

        self::assertSame(['first@example.com', 'second@example.com'], $seen);
        self::assertSame(Command::FAILURE, $tester->getStatusCode(), 'a failed owner must show in the exit code');
        self::assertStringContainsString('flush failed', $tester->getDisplay());
    }

    public function testARateLimitedRunStopsCleanlyAndIsNotAnError(): void
    {
        $sync = $this->createStub(SyncBankAccounts::class);
        $sync->method('execute')->willReturn($this->syncResult('rate_limited'));

        $tester = $this->tester($sync, owners: [$this->user('a@example.com')]);
        $tester->execute(['--write' => true]);

        // supercronic reports every non-zero exit to GlitchTip: a refusal the
        // next run resumes from (MAG-359) is a warning in the logs, not an error.
        $tester->assertCommandIsSuccessful();
        self::assertStringContainsString('N26 : x', $tester->getDisplay());
    }

    public function testAFailedAccountIsNotAGreenRun(): void
    {
        $sync = $this->createStub(SyncBankAccounts::class);
        $sync->method('execute')->willReturn($this->syncResult('failed'));

        $tester = $this->tester($sync, owners: [$this->user('a@example.com')]);
        $tester->execute(['--write' => true]);

        self::assertSame(Command::FAILURE, $tester->getStatusCode());
    }

    public function testWithAnEmailOnlyThatOwnerIsSynced(): void
    {
        $target = $this->user('target@example.com');
        $users = $this->createMock(UserRepository::class);
        $users->expects(self::once())->method('findOneBy')->with(['email' => 'target@example.com'])->willReturn($target);

        $sync = $this->createMock(SyncBankAccounts::class);
        $sync->expects(self::once())->method('execute')->with($target, true)->willReturn($this->syncResult('synced'));

        $connections = $this->createMock(BankConnectionRepository::class);
        $connections->expects(self::never())->method('findOwnersOfActiveConnections');

        $tester = new CommandTester(new SyncBanksCommand($sync, $users, $connections));
        $tester->execute(['email' => 'target@example.com']);

        $tester->assertCommandIsSuccessful();
        self::assertStringContainsString('Rehearsal only', $tester->getDisplay());
    }

    public function testAnUnknownEmailFails(): void
    {
        $users = $this->createStub(UserRepository::class);
        $users->method('findOneBy')->willReturn(null);

        $tester = new CommandTester(new SyncBanksCommand(
            $this->createStub(SyncBankAccounts::class),
            $users,
            $this->createStub(BankConnectionRepository::class),
        ));
        $tester->execute(['email' => 'nobody@example.com']);

        self::assertSame(Command::FAILURE, $tester->getStatusCode());
    }

    /** @param User[] $owners */
    private function tester(SyncBankAccounts $sync, array $owners): CommandTester
    {
        $connections = $this->createStub(BankConnectionRepository::class);
        $connections->method('findOwnersOfActiveConnections')->willReturn($owners);

        return new CommandTester(new SyncBanksCommand($sync, $this->createStub(UserRepository::class), $connections));
    }

    private function user(string $email): User
    {
        return (new User())->setEmail($email);
    }

    /** @return array<string, mixed> */
    private function syncResult(string $status): array
    {
        return [
            'imported' => 0,
            'skipped' => 0,
            'providerCalls' => 2,
            'accounts' => [['bankName' => 'N26', 'accountName' => 'Compte', 'status' => $status, 'message' => 'x']],
        ];
    }
}
