<?php

namespace Maggie\Core\Tests\Security;

use Maggie\Core\Security\TokenCipher;
use Maggie\Core\Security\TokenCipherUnavailable;
use Maggie\Core\Security\TokenUnreadable;
use PHPUnit\Framework\TestCase;

class TokenCipherTest extends TestCase
{
    private const CONTEXT = 'user:01JD0000000000000000000000:01JD0000000000000000000000:google_refresh_token';

    private function cipher(): TokenCipher
    {
        return new TokenCipher(base64_encode(str_repeat('k', 32)));
    }

    public function testASealedValueOpensBackWithItsContext(): void
    {
        $sealed = $this->cipher()->seal('refresh-xyz', self::CONTEXT);

        self::assertSame('refresh-xyz', $this->cipher()->open($sealed, self::CONTEXT));
    }

    public function testTheSealedValueNeverContainsThePlainText(): void
    {
        $sealed = $this->cipher()->seal('refresh-xyz', self::CONTEXT);

        self::assertStringStartsWith('v1.', $sealed);
        self::assertStringNotContainsString('refresh-xyz', $sealed);
        self::assertStringNotContainsString(base64_encode('refresh-xyz'), $sealed);
    }

    public function testTwoSealsOfTheSameValueDiffer(): void
    {
        self::assertNotSame($this->cipher()->seal('refresh-xyz', self::CONTEXT), $this->cipher()->seal('refresh-xyz', self::CONTEXT));
    }

    public function testAValueUnderAnotherContextDoesNotOpen(): void
    {
        $sealed = $this->cipher()->seal('refresh-xyz', self::CONTEXT);

        $this->expectException(TokenUnreadable::class);
        $this->cipher()->open($sealed, str_replace('google_refresh_token', 'google_access_token', self::CONTEXT));
    }

    public function testAValueUnderAnotherKeyDoesNotOpen(): void
    {
        $sealed = $this->cipher()->seal('refresh-xyz', self::CONTEXT);

        $this->expectException(TokenUnreadable::class);
        (new TokenCipher(base64_encode(str_repeat('o', 32))))->open($sealed, self::CONTEXT);
    }

    public function testATamperedValueDoesNotOpen(): void
    {
        $sealed = $this->cipher()->seal('refresh-xyz', self::CONTEXT);
        // A character in the middle of the sealed bytes: every one of its bits counts.
        $tampered = $sealed;
        $tampered[20] = 'A' === $sealed[20] ? 'B' : 'A';

        $this->expectException(TokenUnreadable::class);
        $this->cipher()->open($tampered, self::CONTEXT);
    }

    public function testAValueWithoutTheV1PrefixDoesNotOpen(): void
    {
        $sealed = $this->cipher()->seal('refresh-xyz', self::CONTEXT);

        $this->expectException(TokenUnreadable::class);
        $this->cipher()->open('v2.'.substr($sealed, 3), self::CONTEXT);
    }

    public function testWithoutAKeyNothingIsSealedOrOpened(): void
    {
        $sealed = $this->cipher()->seal('refresh-xyz', self::CONTEXT);
        $cipher = new TokenCipher(null);

        self::assertFalse($cipher->isAvailable());
        foreach ([fn () => $cipher->seal('refresh-xyz', self::CONTEXT), fn () => $cipher->open($sealed, self::CONTEXT)] as $use) {
            try {
                $use();
                self::fail('nothing works without a key');
            } catch (TokenCipherUnavailable $e) {
                self::assertStringContainsString('TOKEN_ENCRYPTION_KEY', $e->getMessage());
            }
        }
    }

    public function testAnEmptyKeyIsNoKey(): void
    {
        self::assertFalse((new TokenCipher(''))->isAvailable());
    }

    public function testAKeyThatIsNot32BytesSaysHowToMakeOne(): void
    {
        $cipher = new TokenCipher(base64_encode(str_repeat('k', 16)));

        self::assertFalse($cipher->isAvailable());
        try {
            $cipher->seal('refresh-xyz', self::CONTEXT);
            self::fail('a 16-byte key must be refused');
        } catch (TokenCipherUnavailable $e) {
            self::assertStringContainsString('16 bytes', $e->getMessage());
            self::assertStringContainsString('openssl rand -base64 32', $e->getMessage());
            self::assertStringNotContainsString(base64_encode(str_repeat('k', 16)), $e->getMessage());
        }
    }

    public function testNoErrorRepeatsTheValue(): void
    {
        $sealed = $this->cipher()->seal('refresh-xyz', self::CONTEXT);
        $attempts = [
            fn () => $this->cipher()->open($sealed, 'another-context'),
            fn () => $this->cipher()->open('refresh-xyz', self::CONTEXT),
            fn () => $this->cipher()->open('v1.refresh-xyz', self::CONTEXT),
            fn () => (new TokenCipher('not base64 !'))->seal('refresh-xyz', self::CONTEXT),
        ];

        foreach ($attempts as $attempt) {
            try {
                $attempt();
                self::fail('the attempt should fail');
            } catch (TokenUnreadable|TokenCipherUnavailable $e) {
                self::assertStringNotContainsString('refresh-xyz', $e->getMessage());
                self::assertStringNotContainsString(substr($sealed, 3), $e->getMessage());
                self::assertStringNotContainsString('not base64', $e->getMessage());
            }
        }
    }
}
