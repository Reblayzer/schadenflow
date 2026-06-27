import { Pipe, PipeTransform } from '@angular/core';
import { Category } from '../core/models/claim.models';
import { categoryLabel } from './claim-labels';

@Pipe({ name: 'category', standalone: true })
export class CategoryPipe implements PipeTransform {
  transform(value: Category | null): string {
    return value ? categoryLabel(value) : '—';
  }
}
