import { describe, it } from 'node:test';
import { strict as assert } from 'node:assert';
import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import '@testing-library/jest-dom';
import { PageLayout } from './PageLayout';
import { notify, notifyAuditId } from '../notify/notify';

describe('PageLayout (debugBorder)', () => {
  it('debugBorder=true iken kırmızı vurguları uygular', () => {
    render(
      <PageLayout title="Kullanıcı Yönetimi" debugBorder>
        <div>içerik</div>
      </PageLayout>
    );

    const title = screen.getByText('Kullanıcı Yönetimi');
    assert.ok(title);

    const headerFlex = title.closest('div')?.parentElement as HTMLElement;
    assert.ok(headerFlex);

    const innerWithBorder = headerFlex.querySelector('div') as HTMLElement | null;
    assert.ok(innerWithBorder);
    assert.match(innerWithBorder.style.borderLeft, /px solid/);

    const debugBadge = screen.getByText('DEBUG');
    assert.ok(debugBadge);
  });
});

describe('PageLayout (notifications)', () => {
  it('notify çağrısı ile mesajı gösterir ve linki sunar', async () => {
    render(
      <PageLayout title="Test">
        <div>Body</div>
      </PageLayout>
    );

    notify({ message: 'Kaydedildi', link: '/admin/audit?event=1' });

    const text = await screen.findByText('Kaydedildi');
    expect(text).toBeInTheDocument();

    const link = await screen.findByText('Audit’te aç');
    expect(link).toHaveAttribute('href', '/admin/audit?event=1');
  });

  it('notifyAuditId yardımcı fonksiyonu audit link üretir', async () => {
    render(
      <PageLayout title="Test2">
        <div>Body</div>
      </PageLayout>
    );

    notifyAuditId('abc-123');

    const link = await screen.findByText('Audit’te aç');
    expect(link).toHaveAttribute('href', '/admin/audit?event=abc-123');
  });
});

describe('PageLayout (descriptionRevealOnHover)', () => {
  it('açıklamayı hover edilene kadar gizler', () => {
    render(
      <PageLayout
        title="Başlık"
        description="Detay açıklama"
        descriptionRevealOnHover
      >
        <div>İçerik</div>
      </PageLayout>,
    );

    const description = screen.getByText('Detay açıklama');
    expect(description).toHaveStyle('opacity: 0');

    const headerRegion = description.closest('[role="region"]') as HTMLElement | null;
    assert.ok(headerRegion);

    fireEvent.mouseEnter(headerRegion);
    expect(description).toHaveStyle('opacity: 1');

    fireEvent.mouseLeave(headerRegion);
    expect(description).toHaveStyle('opacity: 0');
  });
});
