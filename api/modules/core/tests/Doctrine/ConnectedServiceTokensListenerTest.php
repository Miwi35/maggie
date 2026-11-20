<?php

namespace Maggie\Core\Tests\Doctrine;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Doctrine\ORM\Event\PostLoadEventArgs;
use Doctrine\ORM\Event\PreFlushEventArgs;
use Maggie\Core\Doctrine\ConnectedServiceTokensListener;
use Maggie\Core\Entity\User;
use Maggie\Core\Security\ConnectedServiceTokenUnavailable;
use Maggie\Core\Security\TokenCipher;
use Maggie\Core\Security\TokenCipherUnavailable;
use Maggie\Finance\Entity\BankConnection;
use Monolog\Handler\TestHandler;
use Monolog\Level;
use Monolog\Logger;
use Monolog\LogRecord;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * Connected-service tokens are stored sealed and handed out plain (MAG-889);
 * the fixtures set them through the plain setters, as the app does.
 */
class ConnectedServiceTokensListenerTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('ConnectedServiceTokensListenerTest.yaml');
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function reloaded(string $fixture): User
    {
        $this->em()->clear();

        return $this->em()->find(User::class, $this->getFixture($fixture)->getId());
    }

    private function column(string $fixture, string $column): ?string
    {
        return $this->em()->getConnection()->fetchOne(
            sprintf('SELECT %s FROM "user" WHERE id = ?', $column),
            [$this->getFixture($fixture)->getId()->toRfc4122()],
        );
    }

    /**
     * Every record as one line: level, message, context — what would reach the log.
     *
     * @return list<string>
     */
    private function logged(TestHandler $handler): array
    {
        return array_map(
            static fn (LogRecord $record) => $record->level->getName().' '.$record->message.' '.json_encode($record->context),
            $handler->getRecords(),
        );
    }

    public function testGoogleTokensAreStoredSealed(): void
    {
        $user = $this->reloaded('test_user');
        $user->setGoogleRefreshToken('refresh-new')->setGoogleAccessToken('access-new');
        $this->em()->flush();

        foreach (['google_refresh_token' => 'refresh-new', 'google_access_token' => 'access-new'] as $column => $plain) {
            $stored = $this->column('test_user', $column);
            self::assertStringStartsWith('v1.', $stored);
            self::assertStringNotContainsString($plain, $stored);
        }
    }

    public function testAReloadedUserReadsItsTokensBack(): void
    {
        $user = $this->reloaded('test_user');

        self::assertSame('refresh-xyz', $user->getGoogleRefreshToken());
        self::assertSame('access-abc', $user->getGoogleAccessToken());
        self::assertTrue($user->hasGoogleCalendarTokens());
    }

    public function testAFlushWithoutATokenChangeRewritesNothing(): void
    {
        $before = $this->column('test_user', 'google_refresh_token');
        $user = $this->reloaded('test_user');
        $user->setName('Renamed');
        $this->em()->flush();

        self::assertSame($before, $this->column('test_user', 'google_refresh_token'));
    }

    public function testAClearedTokenIsStoredAsNull(): void
    {
        $this->reloaded('test_user')->setGoogleRefreshToken(null);
        $this->em()->flush();

        self::assertNull($this->column('test_user', 'google_refresh_token'));
        self::assertFalse($this->reloaded('test_user')->hasGoogleCalendarTokens());
    }

    public function testATokenCopiedOntoAnotherUserIsUnreadable(): void
    {
        $this->em()->getConnection()->executeStatement(
            'UPDATE "user" SET google_refresh_token = (SELECT google_refresh_token FROM "user" WHERE id = :from) WHERE id = :to',
            ['from' => $this->getFixture('test_user')->getId()->toRfc4122(), 'to' => $this->getFixture('other_user')->getId()->toRfc4122()],
        );

        $other = $this->reloaded('other_user');

        $this->expectException(ConnectedServiceTokenUnavailable::class);
        $other->getGoogleRefreshToken();
    }

    public function testATokenMovedToAnotherUsageIsUnreadable(): void
    {
        $this->em()->getConnection()->executeStatement(
            'UPDATE "user" SET google_refresh_token = google_access_token WHERE id = ?',
            [$this->getFixture('test_user')->getId()->toRfc4122()],
        );

        $user = $this->reloaded('test_user');

        self::assertSame('access-abc', $user->getGoogleAccessToken());
        $this->expectException(ConnectedServiceTokenUnavailable::class);
        $user->getGoogleRefreshToken();
    }

    public function testWithoutAKeyAStoredTokenIsNeverHandedOut(): void
    {
        $user = $this->reloaded('test_user');
        (new ConnectedServiceTokensListener(new TokenCipher(null), new Logger('test')))
            ->postLoad(new PostLoadEventArgs($user, $this->em()));

        // The user still loads — signing in to the app keeps working — but no Google call can be made.
        self::assertSame('fixture@example.com', $user->getEmail());
        self::assertTrue($user->hasGoogleCalendarTokens());
        $this->expectException(ConnectedServiceTokenUnavailable::class);
        $user->getGoogleAccessToken();
    }

    public function testWithoutAKeySigningInStillSavesTheUser(): void
    {
        $user = $this->reloaded('test_user');
        $user->setGoogleRefreshToken('refresh-new');
        $log = new TestHandler();

        (new ConnectedServiceTokensListener(new TokenCipher(null), new Logger('test', [$log])))->preFlush(new PreFlushEventArgs($this->em()));

        self::assertSame(['google_refresh_token' => 'refresh-new'], $user->tokensToSeal(), 'the token stays unsaved, never written in plain');
        self::assertStringNotContainsString('refresh-new', (string) $user->sealedTokens()['google_refresh_token']);
        self::assertTrue($log->hasRecordThatContains('was not saved', Level::Critical));
    }

    public function testWithoutAKeyABankSessionIsNeverWrittenInPlain(): void
    {
        $connection = (new BankConnection())->setBankName('N26')->setUser($this->reloaded('test_user'))->setSessionId('session-42');
        $this->em()->persist($connection);

        $this->expectException(TokenCipherUnavailable::class);
        (new ConnectedServiceTokensListener(new TokenCipher(null), new Logger('test')))->preFlush(new PreFlushEventArgs($this->em()));
    }

    public function testTheLogNeverHoldsTheToken(): void
    {
        $log = new TestHandler();
        $listener = new ConnectedServiceTokensListener(new TokenCipher(null), new Logger('test', [$log]));
        $user = $this->reloaded('test_user');
        $sealed = $user->sealedTokens()['google_refresh_token'];

        $listener->postLoad(new PostLoadEventArgs($user, $this->em()));
        $user->setGoogleRefreshToken('refresh-new');
        $listener->preFlush(new PreFlushEventArgs($this->em()));

        $lines = $this->logged($log);
        self::assertCount(3, $lines, 'two tokens not opened, one not saved');
        foreach ($lines as $line) {
            self::assertStringNotContainsString('refresh-xyz', $line);
            self::assertStringNotContainsString('refresh-new', $line);
            self::assertStringNotContainsString(substr((string) $sealed, 3), $line);
        }
    }
}
