<?php

namespace Maggie\Core\Tests\Security;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;
use Doctrine\Migrations\Exception\AbortMigration;
use Doctrine\ORM\EntityManagerInterface;
use DoctrineMigrations\Version20261120120000;
use Maggie\Core\Entity\User;
use Maggie\Core\Security\ExistingTokensSealer;
use Maggie\Core\Security\TokenCipher;
use Maggie\Finance\Entity\BankConnection;
use Psr\Log\NullLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The migration's two directions (MAG-889): the tokens stored in plain before
 * it are sealed under the very context the entities open them with.
 */
class ExistingTokensSealerTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private Connection $db;
    private TokenCipher $cipher;
    private string $userId;
    private string $connectionId;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Doctrine/fixtures/ConnectedServiceTokensListenerTest.yaml');
        $this->db = self::getContainer()->get('doctrine.dbal.default_connection');
        $this->cipher = new TokenCipher($_SERVER['TOKEN_ENCRYPTION_KEY'] ?? null);

        $user = $this->getFixture('test_user');
        $connection = (new BankConnection())->setBankName('N26')->setUser($user);
        $this->em()->persist($connection);
        $this->em()->flush();
        $this->userId = $user->getId()->toRfc4122();
        $this->connectionId = $connection->getId()->toRfc4122();

        // As they were before the migration: in plain.
        $this->db->executeStatement('UPDATE "user" SET google_access_token = ?, google_refresh_token = ? WHERE id = ?', ['access-abc', 'refresh-xyz', $this->userId]);
        $this->db->executeStatement('UPDATE bank_connection SET session_id = ? WHERE id = ?', ['session-42', $this->connectionId]);
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** @return array{?string, ?string, ?string} */
    private function stored(): array
    {
        return [
            $this->db->fetchOne('SELECT google_access_token FROM "user" WHERE id = ?', [$this->userId]),
            $this->db->fetchOne('SELECT google_refresh_token FROM "user" WHERE id = ?', [$this->userId]),
            $this->db->fetchOne('SELECT session_id FROM bank_connection WHERE id = ?', [$this->connectionId]),
        ];
    }

    public function testPlainTokensInPlaceAreSealed(): void
    {
        self::assertTrue(ExistingTokensSealer::hasPlainTokens($this->db));

        ExistingTokensSealer::seal($this->db, $this->cipher);

        foreach (array_combine(['access-abc', 'refresh-xyz', 'session-42'], $this->stored()) as $plain => $stored) {
            self::assertStringStartsWith('v1.', $stored);
            self::assertStringNotContainsString($plain, $stored);
        }
        self::assertFalse(ExistingTokensSealer::hasPlainTokens($this->db));
    }

    public function testSealingTwiceChangesNothing(): void
    {
        ExistingTokensSealer::seal($this->db, $this->cipher);
        $once = $this->stored();

        self::assertSame(0, ExistingTokensSealer::seal($this->db, $this->cipher));
        self::assertSame($once, $this->stored());
    }

    public function testOpeningPutsThePlainTokensBack(): void
    {
        ExistingTokensSealer::seal($this->db, $this->cipher);
        ExistingTokensSealer::open($this->db, $this->cipher);

        self::assertSame(['access-abc', 'refresh-xyz', 'session-42'], $this->stored());
    }

    public function testASealedTokenOpensThroughTheEntity(): void
    {
        ExistingTokensSealer::seal($this->db, $this->cipher);
        $this->em()->clear();

        $user = $this->em()->find(User::class, $this->getFixture('test_user')->getId());
        $connection = $this->em()->getRepository(BankConnection::class)->findOneBy(['user' => $user]);

        self::assertSame('access-abc', $user->getGoogleAccessToken());
        self::assertSame('refresh-xyz', $user->getGoogleRefreshToken());
        self::assertSame('session-42', $connection->getSessionId());
    }

    public function testTheMigrationSealsAndItsDownPutsThePlainTokensBack(): void
    {
        $migration = $this->migration();
        $migration->up(new Schema());
        $this->execute($migration);
        $migration->postUp(new Schema());

        self::assertFalse(ExistingTokensSealer::hasPlainTokens($this->db));
        self::assertSame('text', $this->sessionIdType());

        $migration = $this->migration();
        $migration->preDown(new Schema());
        $migration->down(new Schema());
        $this->execute($migration);

        self::assertSame(['access-abc', 'refresh-xyz', 'session-42'], $this->stored());
        self::assertSame('character varying', $this->sessionIdType());

        // The schema the rest of the suite expects.
        $this->db->executeStatement('ALTER TABLE bank_connection ALTER session_id TYPE TEXT');
    }

    public function testWithoutAKeyTheMigrationStopsBeforeWriting(): void
    {
        $key = $_SERVER['TOKEN_ENCRYPTION_KEY'] ?? null;
        $_SERVER['TOKEN_ENCRYPTION_KEY'] = '';

        try {
            $this->migration()->up(new Schema());
            self::fail('the migration must not run without a key while a token is in plain');
        } catch (AbortMigration $e) {
            self::assertStringContainsString('maggie-env', $e->getMessage());
        } finally {
            $_SERVER['TOKEN_ENCRYPTION_KEY'] = $key;
        }

        self::assertSame(['access-abc', 'refresh-xyz', 'session-42'], $this->stored());
    }

    private function migration(): Version20261120120000
    {
        // api/migrations is not on the autoloader — the migrations runner loads the files itself.
        require_once \dirname(__DIR__, 4).'/migrations/Version20261120120000.php';

        return new Version20261120120000($this->db, new NullLogger());
    }

    private function execute(AbstractMigration $migration): void
    {
        foreach ($migration->getSql() as $query) {
            $this->db->executeStatement($query->getStatement(), $query->getParameters(), $query->getTypes());
        }
    }

    private function sessionIdType(): string
    {
        return $this->db->fetchOne("SELECT data_type FROM information_schema.columns WHERE table_name = 'bank_connection' AND column_name = 'session_id'");
    }
}
