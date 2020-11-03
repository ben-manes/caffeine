#include "stdio.h"
#include "string.h"
#include "stdlib.h"
#include "assert.h"

/**********************  Parameters You May Want to Change ************/

/* the percentage band within which the number of cold blocks is adjusted */
 
#define MIN_COLD_ALLOC_RATE 5.0
#define MAX_COLD_ALLOC_RATE 5.0

/* however, the above band should be no less than this  */
#define LOWEST_COLD_ALLOC 2 

/* do you allow the number of cold blocks to be adatively adjusted? */
//#define ADAPTIVE_COLD_ALLOC


/* to calculate the number of non-resident cold pages that are promoted (refaulted)
   into hot pages during each window of the size 'NUM_REFAULT_WINDOW'
*/
#define REFAULTS_STAT  /* do you want the statistic ? */
#define NUM_REFAULT_WINDOW 100

/* This specifies from what virtual time (reference event), we start to collect 
 * misses. You can test a warm cache by changin the "0" to some virtual time you want.
 */
#define STAT_START_POINT 0


#define MAX_NONE_RES_PAGE mem_size


/**************************************************************************/

#define TRUE 1
#define FALSE 0

int MIN_cold_alloc;
int MAX_cold_alloc;

typedef struct pf_struct {
  unsigned long  page_num;

  unsigned long ref_times; /* unused */
  unsigned long pf_times;  /* unused */

  int is_resident; 
  int is_cold_page;
  int is_in_clock; 
  int is_in_test;

   /* to simulate the bit set by harware to indicate the page has
      been accessed */  
  char ref_bit;
   
  struct pf_struct * next;
  struct pf_struct * prev;


} page_struct;

/* for debug */
int if_mem_fulled;

/* all pages that are possible to be accessed are in an array headed by the pointer */
page_struct * page_tbl;

unsigned long total_pg_refs, warm_pg_refs; /* if STAT_START_POINT == 0, they are equal */
unsigned long no_dup_refs; /* counter excluding continuous duplicate refs */
unsigned long num_pg_flt; /* number of page faults */

long free_mem_size; /* currently free space */
long mem_size; /* total memory size */
long vm_size; /* total number of distinct pages in the trace */

page_struct * clock_head; 

page_struct * hand_hot; /* equivalent clock_tail */
page_struct * hand_cold; /* equivalent to the tail of resident cold page list */
page_struct * hand_test; 


/* actually we only maintain "num_hot_pages" explicitly*/
#define  num_cold_pages (mem_size-num_hot_pages)

long num_hot_pages; 
long num_non_res_pages;
long clock_size; /* the number of blocks in the clock. Note: the value is varied
                    between mem_size and 2*mem_size */

/* "ColdIncreaseCredits" is to hint how "num_cold_pages" will adjust 
   until it reaches 0:
   > 0: increase the number of cold blocks;
   < 0: decrease the number of hot blocks;
*/
int ColdIncreaseCredits;




