<?php

namespace Maggie\Core\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Core\Entity\User;
use Maggie\Core\Entity\UserPreference;
use Maggie\Core\Mcp\Tool\ListDailyRendezvousTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class ListDailyRendezvousToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Controller/fixtures/user_preference.yaml');
    }

    private function em(): \Doctrine\ORM\EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** @return list<array<string, mixed>> */
    private function call(): array
    {
        $tool = self::getContainer()->get(ListDailyRendezvousTool::class);

        return json_decode($tool(), true, 512, JSON_THROW_ON_ERROR)['users'];
    }

    private function otherUserWithoutPreferences(): User
    {
        $other = new User();
        $other->setEmail('other@example.com');
        $other->setGoogleId('google-other');
        $other->setName('Other');
        $this->em()->persist($other);
        $this->em()->flush();

        return $other;
    }

    public function testTheServiceTokenListsEveryUserWithTheDefaultsWhenNothingIsSet(): void
    {
        $other = $this->otherUserWithoutPreferences();

        $users = $this->call();

        self::assertCount(2, $users);
        $byEmail = array_column($users, null, 'email');
        self::assertSame([
            'userId' => (string) $other->getId(),
            'email' => 'other@example.com',
            'timezone' => 'Europe/Paris',
            'morningDigestEnabled' => true,
            'morningDigestTime' => '07:00',
            'eveningReviewEnabled' => true,
            'eveningReviewTime' => '21:30',
        ], $byEmail['other@example.com']);
    }

    public function testItReadsTheStoredSettings(): void
    {
        $preference = $this->em()->getRepository(UserPreference::class)->findOneBy(['user' => $this->getFixture('test_user')]);
        $preference->setTimezone('America/New_York')->setMorningDigestEnabled(false)->setEveningReviewTime('22:15');
        $this->em()->flush();

        [$user] = $this->call();

        self::assertSame('America/New_York', $user['timezone']);
        self::assertFalse($user['morningDigestEnabled']);
        self::assertSame('22:15', $user['eveningReviewTime']);
    }

    public function testAUserReadsOnlyTheirOwn(): void
    {
        $this->otherUserWithoutPreferences();
        $user = $this->loginFixtureUser();

        $users = $this->call();

        self::assertSame([(string) $user->getId()], array_column($users, 'userId'));
    }
}
