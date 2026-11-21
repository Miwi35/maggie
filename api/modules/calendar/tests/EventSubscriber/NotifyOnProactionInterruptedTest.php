<?php

namespace Maggie\Calendar\Tests\EventSubscriber;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Mcp\Tool\RecordProactionResultTool;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The whole chain (MAG-886): a run recorded completed with an interruption
 * leaves one `message` notification — one sentence and the link to the message.
 */
class NotifyOnProactionInterruptedTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Mcp/fixtures/ProactionDueTest.yaml');
        $this->resetMercure();
    }

    private function record(string $delivery): void
    {
        $data = json_decode(self::getContainer()->get(RecordProactionResultTool::class)(
            (string) $this->getFixture('already_running')->getId(),
            'completed',
            'Votre enveloppe Loisirs est à 72 %, monsieur. Détail…',
            delivery: $delivery,
            topic_key: 'enveloppe-loisirs',
            impact: 'medium',
            evidence: 'solid',
            notice: 'Votre enveloppe Loisirs est à 72 %, monsieur.',
            message_link: '/chat?message=01JABCDEF',
        ), true, 512, JSON_THROW_ON_ERROR);
        self::assertTrue($data['success'] ?? false, json_encode($data, JSON_THROW_ON_ERROR));
    }

    /** @return Notification[] */
    private function notifications(): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Notification::class)->findAll();
    }

    public function testAnInterruptionLeavesOneProactionNotificationWithTheSentenceAndTheLink(): void
    {
        $this->record('interrupt');

        $notifications = $this->notifications();
        self::assertCount(1, $notifications);
        self::assertSame(NotificationType::Proaction, $notifications[0]->getType());
        self::assertSame('Maggie', $notifications[0]->getTitle());
        self::assertSame('Votre enveloppe Loisirs est à 72 %, monsieur.', $notifications[0]->getBody());
        self::assertSame('/chat?message=01JABCDEF', $notifications[0]->getRelatedEntityIri());
        self::assertSame((string) $this->getFixture('test_user')->getId(), (string) $notifications[0]->getUser()->getId());
        $this->assertMercureUpdatePublished('/notifications/');
    }

    public function testAMessageInTheThreadNotifiesNobody(): void
    {
        $this->record('thread');

        self::assertSame([], $this->notifications());
    }

    public function testNothingToSayNotifiesNobody(): void
    {
        $this->record('none');

        self::assertSame([], $this->notifications());
    }
}
