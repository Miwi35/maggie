<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Command\Command;
use Symfony\Component\Console\Tester\CommandTester;

final class UserRoleCommandsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use ElasticsearchAssertionTrait;

    private const ROLE = User::ROLE_PROACTION_TRIGGER;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('UserRoleCommandsTest.yaml');
        $this->resetAsyncTransport();
    }

    public function testGrantAddsTheRoleAndAsksForTheIndex(): void
    {
        $tester = $this->command('app:user:grant', 'target@example.com', self::ROLE);

        $tester->assertCommandIsSuccessful();
        self::assertContains(self::ROLE, $this->roles('target@example.com'));
        $this->assertElasticsearchIndexDispatched(User::class);
        self::assertStringContainsString('issue a new one', $tester->getDisplay());
    }

    public function testGrantTwiceIsASuccessAndStoresTheRoleOnce(): void
    {
        $this->command('app:user:grant', 'target@example.com', self::ROLE);
        $tester = $this->command('app:user:grant', 'target@example.com', self::ROLE);

        $tester->assertCommandIsSuccessful();
        self::assertSame(1, count(array_keys($this->roles('target@example.com'), self::ROLE, true)));
    }

    public function testRevokeRemovesTheRoleAndKeepsTheOthers(): void
    {
        $this->command('app:user:grant', 'target@example.com', self::ROLE);

        $tester = $this->command('app:user:revoke', 'target@example.com', self::ROLE);

        $tester->assertCommandIsSuccessful();
        self::assertSame(['ROLE_USER'], $this->roles('target@example.com'));
        $this->assertElasticsearchIndexDispatched(User::class);
    }

    public function testRevokingAnAbsentRoleIsASuccess(): void
    {
        $this->command('app:user:revoke', 'target@example.com', self::ROLE)->assertCommandIsSuccessful();
    }

    public function testAnUnknownEmailFailsWithAClearMessage(): void
    {
        $tester = $this->command('app:user:grant', 'nobody@example.com', self::ROLE);

        self::assertSame(Command::FAILURE, $tester->getStatusCode());
        self::assertStringContainsString('No user with email "nobody@example.com"', $tester->getDisplay());
    }

    public function testARoleThatIsNotGrantableFails(): void
    {
        foreach (['ROLE_ADMIN', 'ROLE_USER'] as $role) {
            $tester = $this->command('app:user:grant', 'target@example.com', $role);

            self::assertSame(Command::FAILURE, $tester->getStatusCode(), $role);
            self::assertStringContainsString(self::ROLE, $tester->getDisplay());
        }
        self::assertSame(['ROLE_USER'], $this->roles('target@example.com'));
        self::assertSame(Command::FAILURE, $this->command('app:user:revoke', 'target@example.com', 'ROLE_ADMIN')->getStatusCode());
    }

    public function testOtherUsersAreUntouched(): void
    {
        $this->command('app:user:grant', 'target@example.com', self::ROLE);

        self::assertSame(['ROLE_USER'], $this->roles('bystander@example.com'));
    }

    public function testAJwtMintedAfterAGrantCarriesTheRoleAndNotAfterARevoke(): void
    {
        $this->command('app:user:grant', 'target@example.com', self::ROLE);
        self::assertContains(self::ROLE, $this->claims('target@example.com')['roles']);

        $this->command('app:user:revoke', 'target@example.com', self::ROLE);
        self::assertNotContains(self::ROLE, $this->claims('target@example.com')['roles']);
    }

    private function command(string $name, string $email, string $role): CommandTester
    {
        $tester = new CommandTester((new Application(self::$kernel))->find($name));
        $tester->execute(['email' => $email, 'role' => $role]);

        return $tester;
    }

    /** @return list<string> */
    private function roles(string $email): array
    {
        return $this->user($email)->getRoles();
    }

    /** @return array<string, mixed> */
    private function claims(string $email): array
    {
        $jwt = self::getContainer()->get(JWTTokenManagerInterface::class);

        return $jwt->parse($jwt->create($this->user($email)));
    }

    private function user(string $email): User
    {
        /** @var EntityManagerInterface $em */
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(User::class)->findOneBy(['email' => $email]);
    }
}
