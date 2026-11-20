<?php

declare(strict_types=1);

namespace DoctrineMigrations;

use Doctrine\DBAL\Schema\Schema;
use Doctrine\Migrations\AbstractMigration;
use Maggie\Core\Security\ExistingTokensSealer;
use Maggie\Core\Security\TokenCipher;

/**
 * Connected-service tokens are stored sealed (MAG-889): the Google tokens and
 * the bank session already in place are sealed with TOKEN_ENCRYPTION_KEY.
 * Without the key and with a token to seal, the migration stops before
 * writing — and the deploy with it.
 */
final class Version20261120120000 extends AbstractMigration
{
    public function getDescription(): string
    {
        return 'Seal connected-service tokens at rest (MAG-889)';
    }

    public function up(Schema $schema): void
    {
        $this->abortIf(
            !$this->cipher()->isAvailable() && ExistingTokensSealer::hasPlainTokens($this->connection),
            'TOKEN_ENCRYPTION_KEY is missing: add it to the maggie-env Secret before deploying.',
        );

        // A sealed session outgrows 255 characters.
        $this->addSql('ALTER TABLE bank_connection ALTER session_id TYPE TEXT');
    }

    public function postUp(Schema $schema): void
    {
        ExistingTokensSealer::seal($this->connection, $this->cipher());
    }

    public function preDown(Schema $schema): void
    {
        $this->abortIf(
            !$this->cipher()->isAvailable(),
            'TOKEN_ENCRYPTION_KEY is missing: the sealed tokens cannot be put back in plain without it.',
        );

        ExistingTokensSealer::open($this->connection, $this->cipher());
    }

    public function down(Schema $schema): void
    {
        $this->addSql('ALTER TABLE bank_connection ALTER session_id TYPE VARCHAR(255)');
    }

    private function cipher(): TokenCipher
    {
        $key = $_SERVER['TOKEN_ENCRYPTION_KEY'] ?? $_ENV['TOKEN_ENCRYPTION_KEY'] ?? null;

        return new TokenCipher(\is_string($key) ? $key : null);
    }
}
